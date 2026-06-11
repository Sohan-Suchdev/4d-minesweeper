"use strict";

const API_BASE = "http://localhost:8080";

let currentGame = null;
let cellByCoord = new Map();
let highlighted = [];
let deltaMode = false;
let mooreOffsetCache = Object.create(null);

// ---------- DOM identity ----------

// Deterministic mapping: a 4D cell at (w,x,y,z) becomes id="cell-w-x-y-z". Variable arity is supported
// so 1D/2D/3D coordinates serialise to "cell-x", "cell-x-y", "cell-x-y-z" respectively.
const cellIdFor = (coord) => `cell-${coord.join("-")}`;
const coordKey  = (coord) => coord.join(",");
const parseCellId = (id) => id.slice("cell-".length).split("-").map(Number);

// ---------- N-dimensional neighbour maths ----------

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

// Returns every in-bounds Moore-neighbour of coord. Wrap uses ((v % n) + n) % n to handle negatives.
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

// ---------- Rendering ----------

function renderBoard(board) {
    const root = document.getElementById("board");
    root.innerHTML = "";
    const dims = board.dimensions;
    root.className = `board board-${dims.length}d`;
    root.style.gridTemplateColumns = "";

    // Trailing two axes form each sub-grid; leading axes form the outer grid of sub-grids.
    // 1D: outer=[],   inner=[N]      2D: outer=[],    inner=[M,N]
    // 3D: outer=[W],  inner=[X,Y]    4D: outer=[W,X], inner=[Y,Z]
    const innerDims = dims.slice(-2);
    const outerDims = dims.slice(0, -2);

    if (outerDims.length === 0) {
        root.appendChild(buildSubGrid(innerDims, []));
    } else if (outerDims.length === 1) {
        root.style.gridTemplateColumns = `repeat(${outerDims[0]}, auto)`;
        for (let i = 0; i < outerDims[0]; i++) root.appendChild(buildSubGrid(innerDims, [i]));
    } else {
        root.style.gridTemplateColumns = `repeat(${outerDims[1]}, auto)`;
        for (let w = 0; w < outerDims[0]; w++) {
            for (let x = 0; x < outerDims[1]; x++) {
                root.appendChild(buildSubGrid(innerDims, [w, x]));
            }
        }
    }
}

function buildSubGrid(innerDims, outerPrefix) {
    const sub = document.createElement("div");
    sub.className = "sub-grid";

    if (innerDims.length === 1) {
        sub.style.gridTemplateColumns = `repeat(${innerDims[0]}, auto)`;
        for (let i = 0; i < innerDims[0]; i++) sub.appendChild(buildCell([...outerPrefix, i]));
    } else {
        sub.style.gridTemplateColumns = `repeat(${innerDims[1]}, auto)`;
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

    el.addEventListener("click", () => onReveal(coord));
    el.addEventListener("contextmenu", (e) => { e.preventDefault(); onFlag(coord); });
    el.addEventListener("mouseenter", () => onMouseEnter(coord));
    el.addEventListener("mouseleave", clearHighlight);
    return el;
}

function paintCell(el, dto) {
    el.classList.remove("hidden", "revealed", "flagged", "mine", "delta-negative");
    el.textContent = "";
    el.style.color = "";

    if (dto.isFlagged && !dto.isRevealed) {
        el.classList.add("flagged");
        el.textContent = "⚐";
    } else if (dto.isRevealed && dto.isMine === true) {
        el.classList.add("revealed", "mine");
        el.textContent = "✸";
    } else if (dto.isRevealed) {
        el.classList.add("revealed");
        const n = displayNumber(dto);
        if (n !== 0) {
            el.textContent = n;
            // HSL gradient at ~35° per step gives strong neighbouring contrast across the 1..80 range.
            const hue = (Math.abs(n) * 35) % 360;
            el.style.color = `hsl(${hue}, 75%, 65%)`;
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
    const neighbours = mooreNeighbours(coord, currentGame.dimensions, currentGame.wrap);
    // Direct getElementById per neighbour — O(1) per lookup thanks to the deterministic ID scheme.
    for (const nCoord of neighbours) {
        const el = document.getElementById(cellIdFor(nCoord));
        if (el) {
            el.classList.add("neighbour-highlight");
            highlighted.push(el);
        }
    }
}

function clearHighlight() {
    for (const el of highlighted) el.classList.remove("neighbour-highlight");
    highlighted = [];
}

// ---------- Server interaction ----------

async function startNewGame(config) {
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
    const board = await postAction("reveal", coord);
    if (board) updateBoard(board);
}

async function onFlag(coord) {
    if (!currentGame || currentGame.state !== "IN_PROGRESS") return;
    const dto = cellByCoord.get(coordKey(coord));
    if (dto?.isRevealed) return;
    const board = await postAction("flag", coord);
    if (board) updateBoard(board);
}

// ---------- State sync ----------

function updateBoard(board) {
    currentGame = board;
    cellByCoord = new Map();
    for (const c of board.cells) cellByCoord.set(coordKey(c.coordinate), c);

    const flagsPlaced = board.cells.filter((c) => c.isFlagged && !c.isRevealed).length;
    const indicator = document.getElementById("state-indicator");
    indicator.dataset.state = board.state;
    indicator.textContent = board.state.replace("_", " ");
    document.getElementById("mine-counter").textContent =
        `Mines: ${board.totalMines} · Flags: ${flagsPlaced}`;
    document.getElementById("controls").hidden = false;

    clearHighlight();
    renderBoard(board);
}

// ---------- Bootstrap ----------

document.getElementById("setup-form").addEventListener("submit", async (e) => {
    e.preventDefault();
    const config = {
        dimensions: parseInt(document.getElementById("input-dimensions").value, 10),
        size:       parseInt(document.getElementById("input-size").value, 10),
        totalMines: parseInt(document.getElementById("input-mines").value, 10),
        wrap:       document.getElementById("input-wrap").checked,
    };
    const board = await startNewGame(config);
    if (board) updateBoard(board);
});

document.getElementById("input-delta").addEventListener("change", (e) => {
    deltaMode = e.target.checked;
    if (currentGame) renderBoard(currentGame);
});
