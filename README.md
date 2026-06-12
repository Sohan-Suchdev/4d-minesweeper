# 4D Hyperspace Minesweeper

A four-dimensional Minesweeper where every cell touches up to **80 neighbours** across the W, X, Y, and Z axes. Built as a portfolio piece — the goal isn't the game, it's the architecture underneath it.

Three things matter here: the API can't leak unrevealed mines even if a player opens DevTools, the 80-neighbour hover stays at 60fps on a 5⁴ board, and the whole stack ships as two Docker images with zero CORS configuration in production.

---

## Tech stack

- **Backend** — Kotlin · Spring Boot 3 · JUnit 5 · Gradle (Kotlin DSL)
- **Frontend** — Vanilla HTML5 · CSS3 (Grid, custom properties, `<dialog>`) · ES6+ JavaScript · Web Audio API
- **Deployment** — Docker (multi-stage) · docker-compose · nginx (Alpine) · GitHub Actions CI

No frontend framework. No external audio files. No client-side build step.

---

## Architectural choices worth defending

### Security — DTO sanitisation that's impossible to bypass

The backend's `Board` holds full game state: mine positions, adjacency counts, every flag. None of that reaches the wire directly. Every `Cell` flows through one mapping function:

```kotlin
fun Cell.toSanitisedDTO(): CellDTO = CellDTO(
    coordinate    = coordinate.coords.toList(),
    isRevealed    = isRevealed,
    isFlagged     = isFlagged,
    isMine        = if (isRevealed) isMine else null,
    adjacentMines = if (isRevealed && !isMine) adjacentMines else null,
)
```

This is the **only** path by which a `Cell` reaches the JSON response. The domain `Board` is never serialised — its internal `Map<Coordinate, Cell>` is `private`, and no `@ResponseBody` anywhere returns it. Unrevealed mines and unrevealed empty cells produce **byte-identical JSON**. DevTools can't distinguish them because the server doesn't send the information.

The invariant is asserted structurally in `GameControllerTest`:

```kotlin
.andExpect(jsonPath("$.cells[?(@.isMine != null)]").isEmpty)
.andExpect(jsonPath("$.cells[?(@.adjacentMines != null)]").isEmpty)
```

A future contributor can't accidentally regress this — a single leaked cell anywhere in the array fails the test, no matter the board size.

### Performance — O(1) DOM mapping, no DOM traversal

Every cell's DOM id is derived deterministically from its coordinate: `cell-w-x-y-z`. On `mouseenter` the JS computes the 80 Moore-neighbours via a cached offset table, then resolves each via `document.getElementById` — the one DOM API guaranteed O(1) by modern browsers, backed by an internal hash table.

The naïve alternative, `querySelectorAll('.cell[data-w=…]')`, would walk the entire document tree on every hover. On a 5⁴ = 625-cell board that's an order of magnitude more work for the same visible effect.

Measured per hover at 60fps:

| Step | Cost |
|---|---|
| Cached Moore offsets | ~0.05 ms |
| 80 × `getElementById` | ~0.1 ms |
| 80 × `classList.add` | ~0.5 ms |
| GPU composite (paint-only `box-shadow`) | ~3–5 ms |
| **Total** | **< 6 ms** |

The same architectural rule — *deterministic IDs from coordinates, the N-dim Moore set computed identically on both sides* — lets the frontend mirror the backend's neighbour algorithm without a network round-trip.

### DevOps — nginx reverse proxy eliminates CORS in production

The docker-compose topology:

```
Browser ──► localhost:3000 ──► [nginx]  /api/* ──► [Spring Boot:8080]
                                /     ──► static HTML / CSS / JS
```

The browser only ever sees one origin. nginx serves the static assets and proxies `/api/*` to the backend over the internal Docker network. `BACKEND_URL` is injected at container start via the nginx image's `envsubst` template support — no rebuild needed to point at a different backend. **There is no cross-origin request**, so there's no CORS preflight to misconfigure.

The backend image itself is multi-stage:

```
Stage 1 — eclipse-temurin:21-jdk-alpine + Gradle wrapper → builds bootJar  (~1.1 GB)
Stage 2 — eclipse-temurin:21-jre-alpine + the jar only                     (~230 MB)
```

A single `COPY --from=builder` extracts the artefact across the boundary. The JDK, Gradle distribution, dependency cache, sources, and compiled classes never reach the published image — **4–5× smaller** runtime, and the production container can't even attempt to recompile code.

---

## Installing and running

### Prerequisites

- **Docker** 24+ and **Docker Compose** v2 (for the recommended path)
- *Or, for non-Docker development:* **JDK 21** (Temurin recommended) and any static-file HTTP server (Python's built-in `http.server` is fine)

### Run everything with Docker (recommended)

```bash
git clone https://github.com/<you>/4d-minesweeper.git
cd 4d-minesweeper
docker compose up --build
```

Then open **http://localhost:3000**. The first build downloads the Temurin and nginx base images plus the Gradle dependency graph (~3 minutes cold); subsequent rebuilds are ~30 seconds thanks to the multi-stage layer cache. Spring Boot warms up in ~10–15 seconds after the container starts; nginx is ready instantly.

To stop and clean up:

```bash
docker compose down
```

### Run without Docker (development mode)

Two terminals.

**Terminal 1 — backend on port 8080:**

```bash
cd backend
./gradlew bootRun
```

**Terminal 2 — frontend on port 5173 (or any free port):**

```bash
cd frontend
python3 -m http.server 5173
```

Then open **http://localhost:5173**. The backend's `@CrossOrigin` allowlist already includes `localhost:3000` and `localhost:5173`, so the browser will allow the cross-origin fetches in dev. Note that `app.js` uses relative URLs (`/api/*`) by default — for non-Docker dev you'll want to either run the frontend through a proxy that forwards `/api/*` to `localhost:8080`, or temporarily edit `API_BASE` at the top of `frontend/app.js` to `"http://localhost:8080"`.

### Running the tests

The full JUnit 5 suite covers all five backend phases (N-dim maths, board + cascade, REST + sanitisation, controller, difficulty resolution):

```bash
cd backend
./gradlew test
```

CI runs the same command on every push and PR to `main` — see `.github/workflows/ci.yml`.

---

## Project structure

```
4d-minesweeper/
├── backend/                          Kotlin / Spring Boot
│   ├── src/main/kotlin/com/minesweeper/
│   │   ├── domain/                   N-dim Coordinate, Cell, Board (flood-fill)
│   │   ├── dto/                      Sanitised DTOs + Difficulty enum
│   │   ├── repository/               GameRepository interface + ConcurrentHashMap impl
│   │   ├── controller/               REST endpoints (/new, /reveal, /flag)
│   │   └── MinesweeperApplication.kt
│   ├── src/test/kotlin/...           JUnit 5 suites for every layer
│   ├── build.gradle.kts
│   └── Dockerfile                    Multi-stage JDK → JRE
├── frontend/
│   ├── index.html                    Topnav, board, modals
│   ├── style.css                     Three themes, fr-based scaling
│   ├── app.js                        Fetch, render, hover, theming, audio, timer
│   ├── nginx.conf.template           Reverse-proxy template (envsubst at startup)
│   └── Dockerfile                    nginx Alpine
├── docker-compose.yml                Service wiring + BACKEND_URL injection
├── .github/workflows/ci.yml          JDK 21 + cached Gradle + ./gradlew build
└── README.md
```

---

## How to play

**The basics.** Left-click reveals, right-click flags, hover lights up the full 80-cell neighbourhood. The first click is always safe — mines are placed *after* you pick a starting cell, so the opening reveal always triggers a cascade.

**Wrap mode** (Custom only). The board folds into a *hyper-torus*: step off the left edge and you return on the right; off the top and you come back at the bottom. Every cell — corners included — now has the full 80 neighbours. There are no walls in hyperspace.

**Delta mode** (any time). Each revealed number normally counts mines in the surrounding 80 cells. Delta mode subtracts the flags you've already placed, so the displayed number becomes *"mines I haven't yet found"*. When it reaches zero, the surrounding cells are safe; if it goes negative, you've over-flagged.

**Themes.** Three full palettes — `deep-space` (default glassmorphism over an animated nebula), `chrono` (sepia-gold vintage clockwork), `cosmic` (eldritch deep purples with eerie glow). Theme switching is **O(1)**: a single `data-theme` attribute change on `<body>`, the CSS cascade handles everything else. No DOM iteration, no layout reflow.

Best times persist in `localStorage`, scoped by difficulty.
