"use strict";

const API_BASE = "";
const FIXED_DIMENSIONS = 4;
const MIN_FADE_MS = 180;

// ---------- State ----------

let currentGame = null;
let cellByCoord = new Map();
let highlighted = [];
let deltaMode = false;
let firstClickMade = false;
let lastClickedCoord = null;
let previouslyRevealedKeys = new Set();
const mooreOffsetCache = Object.create(null);

// ---------- DOM identity ----------

// Deterministic mapping: cell at (w,x,y,z) becomes id="cell-w-x-y-z". Variable arity supported.
const cellIdFor   = (coord) => `cell-${coord.join("-")}`;
const coordKey    = (coord) => coord.join(",");
const parseCellId = (id)    => id.slice("cell-".length).split("-").map(Number);

// ---------- N-dim Moore neighbour maths ----------

function mooreOffsets(dim) {
    if (mooreOffsetCache[dim]) return mooreOffsetCache[dim];
    let acc = [[]];
    for (let i = 0; i < dim; i++) {
        const next = [];
        for (const prefix of acc) for (const d of [-1, 0, 1]) next.push([...prefix, d]);
        acc = next;
    }
    return (mooreOffsetCache[dim] = acc.filter((o) => o.some((x) => x !== 0)));
}

// Wrap uses ((v % n) + n) % n — JS's % returns negative for negative dividends.
function mooreNeighbours(coord, dimensions, wrap) {
    const out = [];
    for (const offset of mooreOffsets(coord.length)) {
        const next = new Array(coord.length);
        let inBounds = true;
        for (let i = 0; i < coord.length; i++) {
            let v = coord[i] + offset[i];
            if (wrap) {
                v = ((v % dimensions[i]) + dimensions[i]) % dimensions[i];
            } else if (v < 0 || v >= dimensions[i]) {
                inBounds = false;
                break;
            }
            next[i] = v;
        }
        if (inBounds) out.push(next);
    }
    return out;
}

// ---------- Audio (Web Audio API, no external files) ----------

const audio = (() => {
    let ctx = null;
    let muted = localStorage.getItem("muted") === "true";

    function ensure() {
        if (!ctx) {
            const Constructor = window.AudioContext || window.webkitAudioContext;
            if (!Constructor) return null;
            ctx = new Constructor();
        }
        if (ctx.state === "suspended") ctx.resume();
        return ctx;
    }

    function tone(freq, duration, type = "sine", volume = 0.08) {
        if (muted) return;
        const c = ensure(); if (!c) return;
        const osc = c.createOscillator();
        const gain = c.createGain();
        osc.type = type;
        osc.frequency.value = freq;
        gain.gain.setValueAtTime(volume, c.currentTime);
        gain.gain.exponentialRampToValueAtTime(0.0001, c.currentTime + duration);
        osc.connect(gain).connect(c.destination);
        osc.start();
        osc.stop(c.currentTime + duration);
    }

    function noise(duration, volume = 0.2) {
        if (muted) return;
        const c = ensure(); if (!c) return;
        const size = Math.floor(c.sampleRate * duration);
        const buf = c.createBuffer(1, size, c.sampleRate);
        const data = buf.getChannelData(0);
        for (let i = 0; i < size; i++) data[i] = (Math.random() * 2 - 1) * (1 - i / size);
        const src = c.createBufferSource();
        const gain = c.createGain();
        gain.gain.value = volume;
        src.buffer = buf;
        src.connect(gain).connect(c.destination);
        src.start();
    }

    return {
        reveal()  { tone(520, 0.06, "triangle", 0.06); },
        flag()    { tone(880, 0.05, "square", 0.05); },
        explode() { noise(0.5, 0.28); tone(70, 0.4, "sawtooth", 0.2); },
        win() {
            [523.25, 659.25, 783.99, 1046.50].forEach((f, i) => {
                setTimeout(() => tone(f, 0.13, "sine", 0.08), i * 90);
            });
        },
        setMuted(m) { muted = m; localStorage.setItem("muted", String(m)); },
        isMuted()   { return muted; },
    };
})();

// ---------- Timer ----------

let timerInterval = null;
let timerStart = null;
let elapsedSeconds = 0;

function startTimer() {
    if (timerInterval) return;
    timerStart = Date.now();
    timerInterval = setInterval(() => {
        elapsedSeconds = Math.floor((Date.now() - timerStart) / 1000);
        document.getElementById("timer").textContent = formatSeconds(elapsedSeconds);
    }, 200);
}

function stopTimer() {
    if (timerInterval) { clearInterval(timerInterval); timerInterval = null; }
}

function resetTimer() {
    stopTimer();
    elapsedSeconds = 0;
    timerStart = null;
    document.getElementById("timer").textContent = "000";
}

function formatSeconds(s) {
    if (s < 0)   return "000";
    if (s > 999) return "999";
    return String(s).padStart(3, "0");
}

// ---------- High score ----------

const bestKeyFor = (difficulty) => `bestTime_${difficulty}`;

function loadBest(difficulty) {
    const raw = localStorage.getItem(bestKeyFor(difficulty));
    const n = raw ? parseInt(raw, 10) : NaN;
    return Number.isFinite(n) ? n : null;
}

function recordTime(difficulty, seconds) {
    if (!["EASY", "MEDIUM", "HARD"].includes(difficulty)) return null;
    const current = loadBest(difficulty);
    if (current === null || seconds < current) {
        localStorage.setItem(bestKeyFor(difficulty), String(seconds));
        return seconds;
    }
    return null;
}

// ---------- Theme ----------

function applyTheme(name) {
    document.body.dataset.theme = name;
    localStorage.setItem("theme", name);
}

// ---------- Difficulty / config ----------

function getGameConfig() {
    const difficulty = document.getElementById("difficulty-select").value;
    if (difficulty === "CUSTOM") {
        return {
            dimensions: FIXED_DIMENSIONS,
            size:       parseInt(document.getElementById("input-size").value, 10),
            totalMines: parseInt(document.getElementById("input-mines").value, 10),
            wrap:       document.getElementById("input-wrap").checked,
        };
    }
    // Preset modes: backend resolves the difficulty enum; wrap is preset-off by design.
    return { difficulty, dimensions: FIXED_DIMENSIONS, wrap: false };
}

function isCustomMode() {
    return document.getElementById("difficulty-select").value === "CUSTOM";
}

// ---------- Rendering ----------

function renderBoard(board) {
    const root = document.getElementById("board");
    root.innerHTML = "";
    root.classList.remove("shake");
    const dims = board.dimensions;

    // Trailing two axes form the inner sub-grids; leading axes form the outer grid.
    // 4D (W,X,Y,Z): outer=[W,X], inner=[Y,Z]. CSS variables drive the fr-based track counts.
    const innerDims = dims.slice(-2);
    const outerDims = dims.slice(0, -2);

    if (outerDims.length === 0) {
        root.style.setProperty("--outer-cols", 1);
        root.style.setProperty("--outer-rows", 1);
        root.appendChild(buildSubGrid(innerDims, []));
    } else if (outerDims.length === 1) {
        root.style.setProperty("--outer-cols", outerDims[0]);
        root.style.setProperty("--outer-rows", 1);
        for (let i = 0; i < outerDims[0]; i++) root.appendChild(buildSubGrid(innerDims, [i]));
    } else {
        root.style.setProperty("--outer-cols", outerDims[1]);
        root.style.setProperty("--outer-rows", outerDims[0]);
        for (let w = 0; w < outerDims[0]; w++) {
            for (let x = 0; x < outerDims[1]; x++) root.appendChild(buildSubGrid(innerDims, [w, x]));
        }
    }

    if (lastClickedCoord && currentGame?.state === "LOST") {
        const el = document.getElementById(cellIdFor(lastClickedCoord));
        if (el) el.classList.add("mine-detonated");
    }
}

function buildSubGrid(innerDims, outerPrefix) {
    const sub = document.createElement("div");
    sub.className = "sub-grid";
    if (innerDims.length === 1) {
        sub.style.setProperty("--inner-cols", innerDims[0]);
        sub.style.setProperty("--inner-rows", 1);
        for (let i = 0; i < innerDims[0]; i++) sub.appendChild(buildCell([...outerPrefix, i]));
    } else {
        sub.style.setProperty("--inner-cols", innerDims[1]);
        sub.style.setProperty("--inner-rows", innerDims[0]);
        for (let r = 0; r < innerDims[0]; r++) {
            for (let c = 0; c < innerDims[1]; c++) sub.appendChild(buildCell([...outerPrefix, r, c]));
        }
    }
    return sub;
}

function buildCell(coord) {
    const el = document.createElement("div");
    el.id = cellIdFor(coord);
    el.className = "cell hidden";
    const dto = cellByCoord.get(coordKey(coord));
    if (dto) paintCell(el, dto);

    el.addEventListener("click",       () => onReveal(coord));
    el.addEventListener("contextmenu", (e) => { e.preventDefault(); onFlag(coord); });
    el.addEventListener("mouseenter",  () => onMouseEnter(coord));
    el.addEventListener("mouseleave",  clearHighlight);
    return el;
}

function paintCell(el, dto) {
    el.classList.remove("hidden", "revealed", "flagged", "mine", "fresh-reveal", "delta-negative", "mine-detonated");
    el.textContent = "";
    el.style.color = "";

    const key = coordKey(dto.coordinate);
    const justRevealed = dto.isRevealed && !previouslyRevealedKeys.has(key);

    if (dto.isFlagged && !dto.isRevealed) {
        el.classList.add("flagged");
        el.textContent = "⚑";
    } else if (dto.isRevealed && dto.isMine === true) {
        el.classList.add("revealed", "mine");
        el.textContent = "✸";
        if (justRevealed) el.classList.add("fresh-reveal");
    } else if (dto.isRevealed) {
        el.classList.add("revealed");
        if (justRevealed) el.classList.add("fresh-reveal");
        const n = displayNumber(dto);
        if (n !== 0) {
            el.textContent = n;
            const hue = (Math.abs(n) * 35) % 360;
            el.style.color = `hsl(${hue}, 80%, 65%)`;
            if (n < 0) el.classList.add("delta-negative");
        }
    } else {
        el.classList.add("hidden");
    }
}

// ---------- Delta Mode ----------

function displayNumber(dto) {
    const base = dto.adjacentMines ?? 0;
    if (!deltaMode || base === 0) return base;
    return base - countFlaggedNeighbours(dto.coordinate);
}

function countFlaggedNeighbours(coord) {
    let count = 0;
    for (const n of mooreNeighbours(coord, currentGame.dimensions, currentGame.wrap)) {
        if (cellByCoord.get(coordKey(n))?.isFlagged) count++;
    }
    return count;
}

// ---------- Hover highlight ----------

function onMouseEnter(coord) {
    clearHighlight();
    if (!currentGame) return;
    // Direct getElementById per neighbour — O(1) per lookup via the deterministic ID scheme.
    for (const nCoord of mooreNeighbours(coord, currentGame.dimensions, currentGame.wrap)) {
        const el = document.getElementById(cellIdFor(nCoord));
        if (el) { el.classList.add("neighbour-highlight"); highlighted.push(el); }
    }
}

function clearHighlight() {
    for (const el of highlighted) el.classList.remove("neighbour-highlight");
    highlighted = [];
}

// ---------- Modals ----------

function showModal(id) { document.getElementById(id).showModal(); }

function wireModalButtons() {
    document.querySelectorAll(".modal [data-action]").forEach((btn) => {
        btn.addEventListener("click", () => {
            const action = btn.dataset.action;
            btn.closest("dialog").close();
            if (action === "play-again") startNewGame();
        });
    });
}

function fillEndOfGameModal(modalId, timeFieldId, bestFieldId, newBest) {
    document.getElementById(timeFieldId).textContent = formatSeconds(elapsedSeconds);
    const difficulty = document.getElementById("difficulty-select").value;
    const best = newBest ?? loadBest(difficulty);
    document.getElementById(bestFieldId).textContent = best !== null ? formatSeconds(best) : "—";
    showModal(modalId);
}

// ---------- Server interaction ----------

async function fetchNewGame(config) {
    const res = await fetch(`${API_BASE}/api/game/new`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(config),
    });
    if (!res.ok) {
        const err = await res.json().catch(() => ({ error: res.statusText }));
        alert(`Could not start: ${err.error}`);
        return null;
    }
    return res.json();
}

async function postAction(path, coord) {
    const res = await fetch(`${API_BASE}/api/game/${currentGame.id}/${path}`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ coordinate: coord }),
    });
    if (!res.ok) return null;
    return res.json();
}

async function onReveal(coord) {
    if (!currentGame || currentGame.state !== "IN_PROGRESS") return;
    const dto = cellByCoord.get(coordKey(coord));
    if (dto?.isRevealed || dto?.isFlagged) return;

    if (!firstClickMade) { firstClickMade = true; startTimer(); }
    lastClickedCoord = coord;
    audio.reveal();
    const board = await postAction("reveal", coord);
    if (board) updateBoard(board);
}

async function onFlag(coord) {
    if (!currentGame || currentGame.state !== "IN_PROGRESS") return;
    const dto = cellByCoord.get(coordKey(coord));
    if (dto?.isRevealed) return;

    audio.flag();
    const board = await postAction("flag", coord);
    if (board) updateBoard(board);
}

// ---------- State sync ----------

function updateBoard(board) {
    currentGame = board;
    cellByCoord = new Map();
    for (const c of board.cells) cellByCoord.set(coordKey(c.coordinate), c);

    const flagsPlaced = board.cells.filter((c) => c.isFlagged && !c.isRevealed).length;
    const remaining = board.totalMines - flagsPlaced;
    document.getElementById("flag-counter").textContent =
        remaining < 0 ? "-" + String(Math.abs(remaining)).padStart(2, "0") : formatSeconds(remaining);

    const indicator = document.getElementById("state-indicator");
    indicator.dataset.state = board.state;
    indicator.textContent = board.state.replace("_", " ");

    clearHighlight();
    renderBoard(board);

    previouslyRevealedKeys = new Set();
    for (const c of board.cells) if (c.isRevealed) previouslyRevealedKeys.add(coordKey(c.coordinate));

    if (board.state === "LOST")      handleLoss();
    else if (board.state === "WON")  handleWin();
}

function handleLoss() {
    stopTimer();
    audio.explode();
    const boardEl = document.getElementById("board");
    boardEl.classList.add("shake");
    setTimeout(() => boardEl.classList.remove("shake"), 500);
    setTimeout(() => fillEndOfGameModal("modal-game-over", "game-over-time", "game-over-best", null), 700);
}

function handleWin() {
    stopTimer();
    audio.win();
    const difficulty = document.getElementById("difficulty-select").value;
    const newBest = recordTime(difficulty, elapsedSeconds);
    document.getElementById("level-cleared-new-best").hidden = (newBest === null);
    setTimeout(() => fillEndOfGameModal("modal-level-cleared", "level-cleared-time", "level-cleared-best", newBest), 300);
}

// ---------- Game lifecycle ----------

// Fades the board out, awaits the fetch (with a perceptible minimum fade window), then re-renders and fades back in.
async function startNewGame() {
    const boardEl = document.getElementById("board");
    boardEl.classList.add("loading");
    const minFade = new Promise((r) => setTimeout(r, MIN_FADE_MS));
    const [dto] = await Promise.all([fetchNewGame(getGameConfig()), minFade]);
    if (!dto) { boardEl.classList.remove("loading"); return; }
    resetTimer();
    firstClickMade = false;
    lastClickedCoord = null;
    previouslyRevealedKeys = new Set();
    updateBoard(dto);
    requestAnimationFrame(() => boardEl.classList.remove("loading"));
}

// ---------- Mini-grid demo (3⁴ board, centre cell highlighted, 80 neighbours pulsing) ----------

function buildMiniGrid() {
    const container = document.getElementById("mini-grid-demo");
    if (!container) return;
    container.innerHTML = "";
    let staggerIndex = 0;
    for (let ow = 0; ow < 3; ow++) {
        for (let ox = 0; ox < 3; ox++) {
            const sub = document.createElement("div");
            sub.className = "mini-sub";
            for (let iy = 0; iy < 3; iy++) {
                for (let iz = 0; iz < 3; iz++) {
                    const cell = document.createElement("div");
                    cell.className = "mini-cell";
                    const isCentre = ow === 1 && ox === 1 && iy === 1 && iz === 1;
                    if (isCentre) cell.classList.add("mini-cell-centre");
                    else {
                        cell.classList.add("mini-cell-neighbour");
                        // Staggered delay produces a hyperspatial wave across the 80 surrounding cells.
                        cell.style.animationDelay = `${staggerIndex * 22}ms`;
                        staggerIndex++;
                    }
                    sub.appendChild(cell);
                }
            }
            container.appendChild(sub);
        }
    }
}

// ---------- Bootstrap ----------

(function init() {
    const savedTheme = localStorage.getItem("theme") || "deep-space";
    applyTheme(savedTheme);
    document.getElementById("theme-select").value = savedTheme;
    document.getElementById("mute-btn").textContent = audio.isMuted() ? "🔇" : "🔊";

    const customControls = document.getElementById("custom-controls");

    document.getElementById("difficulty-select").addEventListener("change", (e) => {
        const isCustom = e.target.value === "CUSTOM";
        customControls.classList.toggle("expanded", isCustom);
        if (!isCustom) startNewGame();
    });

    document.getElementById("theme-select").addEventListener("change", (e) => {
        applyTheme(e.target.value);
    });

    document.getElementById("input-delta").addEventListener("change", (e) => {
        deltaMode = e.target.checked;
        if (currentGame) renderBoard(currentGame);
    });

    document.getElementById("new-game-btn").addEventListener("click", () => {
        if (isCustomMode()) startNewGame();
    });

    document.getElementById("mute-btn").addEventListener("click", () => {
        const muted = !audio.isMuted();
        audio.setMuted(muted);
        document.getElementById("mute-btn").textContent = muted ? "🔇" : "🔊";
    });

    document.getElementById("info-btn").addEventListener("click", () => showModal("modal-instructions"));

    wireModalButtons();
    buildMiniGrid();

    // Land on a playable board so the user doesn't see an empty container on load.
    startNewGame();
})();
