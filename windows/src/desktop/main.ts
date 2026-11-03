// The desktop Mochi's own window (mochi.html): draws him, and turns clicks and
// drags on him into the other Mochis around him, flights home, the wardrobe
// and a new spot. Port of DesktopBotView + the mouse half of
// DesktopMochiController (DesktopMochi.swift); the other Mochis around him
// follow the Mac's corner island (CornerIsland.swift).
//
// What he shows comes from the island (DESKTOP_EVENTS.state), where he is from
// Rust. The page draws only while the window is on screen, at 30 fps awake
// like the Mac, and a few frames a second asleep — with no cursor polling at
// all while he sleeps.

import { Bridge, emitToWindow, onEvent, type DesktopFan, type DesktopMode } from "../core/bridge";
import type { BotEmoteName } from "../core/layout";
import { Sound } from "../core/sound";
import type { AgentTask } from "../core/state";
import { BotEngine } from "../mochi/engine";
import { createMiniBot, pruneMiniBots, syncMiniBotStates, tickMiniBots } from "../mochi/minibots";
import {
  DESKTOP_EVENTS, DOUBLE_CLICK_MS, DRAG_THRESHOLD, FAN_BODY, PANEL_SIZE, agentActive, fanOrder, fanPoints,
  gaze, isOverBody, layerDragTopLeft, lookOrigin, pointerDistance, shouldSleep, windowDragTopLeft,
  type DesktopSnapshot, type FanMochi, type Point,
} from "../mochi/desktop-logic";

const ISLAND = "island";

/**
 * Width Mochi is drawn at. The engine's body radius is 0.3 × width, so this
 * gives the 28.8 px body the hit test uses, and leaves room around him for
 * hats, hands and hearts inside the 120 px window.
 */
const DRAW_W = PANEL_SIZE * 0.8;
const SIDE = (PANEL_SIZE - DRAW_W) / 2;
/** Frame pacing: TimelineView(minimumInterval: 1/30) awake; asleep, barely. */
const AWAKE_FRAME_MS = 1000 / 30;
const ASLEEP_FRAME_MS = 250;
/** The other Mochis go back in after this long without the pointer near them. */
const FAN_IDLE_MS = 6000;
/** A swapped Mochi's flight into him (CornerArcView.swapDuration). */
const SWAP_MS = 400;

class DesktopMochi {
  private engine = new BotEngine();
  private canvas: HTMLCanvasElement;
  private mode: DesktopMode = "off";

  private snap: DesktopSnapshot = {
    state: "idle", outfit: "none", soundEnabled: true, soundVolume: 0.12, paused: false, dancing: false,
    others: [],
  };

  /** The other Mochis are out: where his square sits in the grown window. */
  private fan: DesktopFan | null = null;
  private fanEl: HTMLElement;
  /** Their order on the half circle, kept across swaps. */
  private fanIds: string[] = [];
  /** What is drawn, to rebuild only when the pills change. */
  private fanKey = "";
  /** A swap left this place free for the one that was in focus. */
  private fanSlot: number | null = null;
  private fanIdle: number | null = null;

  private visible = false;
  private asleep = false;
  /** Whether the island has told us anything yet. */
  private informed = false;
  private lastAgentActive = performance.now();
  /** Pointer in window coordinates, and when the page last heard of it. */
  private cursor: Point | null = null;
  private lastPointer = -Infinity;

  private timer: number | null = null;
  private lastDraw = 0;

  /** Canvas offset inside the window: non-zero only in a layer-shell drag. */
  private offset: Point = { x: 0, y: 0 };
  private press: { client: Point; screen: Point } | null = null;
  private dragging = false;
  /** Window's top-left corner when the drag began (mode's space). */
  private dragOrigin: Point | null = null;
  private dragTopLeft: Point | null = null;
  private moveFrame = false;
  private pokeTimer: number | null = null;

  constructor(canvas: HTMLCanvasElement, fanEl: HTMLElement) {
    this.canvas = canvas;
    this.fanEl = fanEl;
    const dpr = this.dpr();
    canvas.width = Math.round(PANEL_SIZE * dpr);
    canvas.height = Math.round(PANEL_SIZE * dpr);
    this.engine.particleOverhang = 0;
    this.engine.setState("idle", true);
    // Three pokes: dizzy, and the island shows the confused view (as on macOS).
    this.engine.onDizzy = () => void emitToWindow(ISLAND, DESKTOP_EVENTS.dizzy);
    this.wireInput();
  }

  async start() {
    void Sound.preload();
    const info = await Bridge.desktopInfo();
    if (info) this.mode = info.mode;

    await onEvent<DesktopSnapshot>(DESKTOP_EVENTS.state, (s) => this.onSnapshot(s));
    await onEvent<{ emote: BotEmoteName; duration: number }>(DESKTOP_EVENTS.emote, (e) => {
      this.lastAgentActive = performance.now();
      this.wake();
      this.engine.triggerEmote(e.emote, e.duration);
      this.schedule();
    });
    await onEvent<Point>(DESKTOP_EVENTS.cursor, (p) => this.notePointer(p));
    await onEvent<boolean>(DESKTOP_EVENTS.visible, (on) => this.setVisible(on));
    // Rust took the grown window back (a drag, a flight home).
    await onEvent<null>(DESKTOP_EVENTS.fanClosed, () => this.closeFan(false));
    await onEvent<string>(DESKTOP_EVENTS.flight, (kind) => {
      // He fades in on the way out of the island (alphaValue 0 → 1 on macOS).
      if (kind !== "out") return;
      this.canvas.style.transition = "none";
      this.canvas.style.opacity = "0";
      void this.canvas.offsetWidth;
      this.canvas.style.transition = "";
      this.canvas.style.opacity = "1";
    });
    await emitToWindow(ISLAND, DESKTOP_EVENTS.ready);
  }

  // ── State ───────────────────────────────────────────────────────────────────

  private onSnapshot(s: DesktopSnapshot) {
    this.snap = s;
    this.informed = true;
    Sound.setEnabled(s.soundEnabled);
    Sound.setVolume(s.soundVolume);
    if (agentActive(s.state)) this.lastAgentActive = performance.now();
    if (!this.asleep) this.engine.setState(s.state);
    if (this.fan) this.drawFan();
    this.updateSleep(performance.now());
    this.schedule();
  }

  private setVisible(on: boolean) {
    this.visible = on;
    if (on) {
      // The two pages may have started in either order: ask again if needed.
      if (!this.informed) void emitToWindow(ISLAND, DESKTOP_EVENTS.ready);
      // Landing counts as something going on: he looks around for a while.
      this.lastAgentActive = performance.now();
      this.asleep = false;
      this.engine.setState(this.snap.state, true);
      this.engine.setOutfit(this.snap.outfit, false);
      this.schedule();
      return;
    }
    this.cancelPoke();
    this.closeFan(false);
    this.press = null;
    this.dragging = false;
    this.setOffset({ x: 0, y: 0 });
    if (this.timer != null) window.clearTimeout(this.timer);
    this.timer = null;
    Sound.idle();
  }

  private updateSleep(now: number) {
    if (!this.visible) return;
    // Dancing keeps him up: asleep he would bounce at a few frames a second.
    if (this.snap.dancing && !this.snap.paused) this.lastAgentActive = now;
    const distance = pointerDistance(this.mode === "poll", this.cursor, now - this.lastPointer);
    const sleep =
      !this.dragging &&
      !this.fan &&
      (this.snap.paused || shouldSleep((now - this.lastAgentActive) / 1000, distance));
    if (sleep === this.asleep) return;
    this.asleep = sleep;
    this.engine.setState(sleep ? "sleeping" : this.snap.state);
    // Asleep, Rust stops the cursor poll; his whole little window then listens
    // for the pointer, and the first move over it wakes him.
    void Bridge.desktopSetAsleep(sleep);
    if (sleep) Sound.idle();
    this.schedule();
  }

  private wake() {
    if (!this.asleep) return;
    this.updateSleep(performance.now());
  }

  /** Pointer in window coordinates: his eyes, and waking up. */
  private notePointer(p: Point) {
    this.cursor = p;
    this.lastPointer = performance.now();
    if (this.asleep) this.wake();
    this.schedule();
  }

  // ── Frames ──────────────────────────────────────────────────────────────────

  private schedule() {
    if (this.timer != null || !this.visible) return;
    const wait = this.asleep ? ASLEEP_FRAME_MS : AWAKE_FRAME_MS;
    const due = Math.max(0, this.lastDraw + wait - performance.now());
    this.timer = window.setTimeout(() => {
      // Awake, frames line up with the display; asleep, a timer is enough.
      if (this.asleep) {
        this.timer = null;
        this.frame(performance.now());
      } else {
        requestAnimationFrame((t) => {
          this.timer = null;
          this.frame(t);
        });
      }
    }, due);
  }

  private frame(now: number) {
    if (!this.visible) return;
    const dt = Math.min(0.05, Math.max(0, (now - this.lastDraw) / 1000));
    this.lastDraw = now;
    this.updateSleep(now);
    this.draw(dt, now);
    this.schedule();
  }

  private draw(dt: number, now: number) {
    const ctx = this.canvas.getContext("2d");
    if (!ctx) return;
    const engine = this.engine;
    engine.setOutfit(this.snap.outfit, true);
    if (!this.asleep) {
      // Windows: the global cursor. Linux: only while the pointer is over him.
      const fresh = this.mode === "poll" || now - this.lastPointer < 1500;
      const at = this.fan ?? { x: 0, y: 0 };
      const g = this.cursor && fresh ? gaze(lookOrigin(at.x, at.y), this.cursor) : { lookX: 0, lookY: 0 };
      engine.lookX = g.lookX;
      engine.lookY = g.lookY;
    }
    // Dances while Spotify plays, by the compact island's rules.
    engine.setDancing(this.snap.dancing && !this.asleep);
    engine.update(dt);
    const dpr = this.dpr();
    // Dragged onto a monitor of another scale, the bitmap follows the ratio.
    if (this.canvas.width !== Math.round(PANEL_SIZE * dpr)) {
      this.canvas.width = Math.round(PANEL_SIZE * dpr);
      this.canvas.height = Math.round(PANEL_SIZE * dpr);
    }
    ctx.setTransform(dpr, 0, 0, dpr, SIDE * dpr, 0);
    ctx.clearRect(-SIDE, 0, PANEL_SIZE, PANEL_SIZE);
    ctx.save();
    engine.applyDance(ctx, DRAW_W, PANEL_SIZE);
    engine.draw(ctx, DRAW_W, PANEL_SIZE);
    ctx.restore();
    if (this.fan) tickMiniBots(dt);
  }

  private dpr(): number {
    return Math.min(2, window.devicePixelRatio || 1);
  }

  // ── Input ───────────────────────────────────────────────────────────────────

  private local(e: MouseEvent): Point {
    return { x: e.clientX - this.offset.x, y: e.clientY - this.offset.y };
  }

  private wireInput() {
    document.addEventListener("pointerdown", (e) => {
      Sound.resume();
      // The other Mochis are out: a press beside them (not on him) puts them away.
      if (this.fan && !isOverBody(this.local(e))) {
        const onMini = e.target instanceof Element && e.target.closest(".fan-mini");
        if (!onMini) this.closeFan(true);
        return;
      }
      if (e.button !== 0 || !isOverBody(this.local(e))) return;
      this.press = { client: this.local(e), screen: { x: e.screenX, y: e.screenY } };
      this.dragging = false;
      try {
        document.documentElement.setPointerCapture(e.pointerId);
      } catch {
        /* capture is a nicety */
      }
    });

    document.addEventListener("pointermove", (e) => {
      if (this.fan) this.keepFan();
      if (!this.dragging) this.notePointer(this.local(e));
      const press = this.press;
      if (!press) return;
      if (!this.dragging) {
        const l = this.local(e);
        if (Math.hypot(l.x - press.client.x, l.y - press.client.y) <= DRAG_THRESHOLD) return;
        this.beginDrag();
      }
      this.dragTo(e);
    });

    document.addEventListener("pointerup", (e) => {
      const press = this.press;
      this.press = null;
      if (!press) return;
      if (this.dragging) {
        this.endDrag(e);
        return;
      }
      // Single click → the other Mochis come out around him (or go back in),
      // once it is clear it isn't a double click (that one sends him home).
      this.cancelPoke();
      this.pokeTimer = window.setTimeout(() => {
        this.pokeTimer = null;
        this.lastAgentActive = performance.now();
        this.wake();
        this.schedule();
        if (this.fan) this.closeFan(true);
        else this.openFan();
      }, DOUBLE_CLICK_MS);
    });

    document.addEventListener("dblclick", (e) => {
      if (!isOverBody(this.local(e))) return;
      this.cancelPoke();
      void emitToWindow(ISLAND, DESKTOP_EVENTS.home);
    });

    document.addEventListener("contextmenu", (e) => {
      e.preventDefault();
      if (isOverBody(this.local(e))) void emitToWindow(ISLAND, DESKTOP_EVENTS.wardrobe);
    });

    document.addEventListener("pointerleave", () => {
      if (this.mode !== "poll" && !this.dragging) this.cursor = null;
    });

    // A layer-shell drag stretches the window over the display; once it is
    // back to its own size, so is the canvas.
    window.addEventListener("resize", () => {
      if (!this.dragging && window.innerWidth <= PANEL_SIZE + 20) this.setOffset({ x: 0, y: 0 });
    });
  }

  private cancelPoke() {
    if (this.pokeTimer != null) window.clearTimeout(this.pokeTimer);
    this.pokeTimer = null;
  }

  private beginDrag() {
    this.dragging = true;
    this.cancelPoke();
    // Rust shrinks the window back as the drag begins (desktop_mochi_drag_begin).
    this.closeFan(false);
    this.dragOrigin = null;
    this.dragTopLeft = null;
    void Bridge.desktopDragBegin().then((pos) => {
      if (!pos) {
        this.dragging = false;
        this.press = null;
        return;
      }
      this.dragOrigin = { x: pos[0], y: pos[1] };
    });
  }

  /** Linux: the page drives the drag. Windows: the cursor poll carries him. */
  private dragTo(e: PointerEvent) {
    const origin = this.dragOrigin;
    const press = this.press;
    if (!origin || !press || this.mode === "poll") return;
    if (this.mode === "layer") {
      const applied = window.innerWidth > PANEL_SIZE + 20;
      const topLeft = layerDragTopLeft(origin, { x: e.clientX, y: e.clientY }, press.client, applied);
      this.dragTopLeft = topLeft;
      this.setOffset(applied ? topLeft : { x: topLeft.x - origin.x, y: topLeft.y - origin.y });
      return;
    }
    if (this.mode === "window") {
      const topLeft = windowDragTopLeft(origin, { x: e.screenX, y: e.screenY }, press.screen, this.dpr());
      this.dragTopLeft = topLeft;
      if (this.moveFrame) return;
      this.moveFrame = true;
      requestAnimationFrame(() => {
        this.moveFrame = false;
        const p = this.dragTopLeft;
        if (p && this.dragging) void Bridge.desktopDragMove(p.x, p.y);
      });
    }
  }

  private endDrag(e: PointerEvent) {
    this.dragTo(e);
    this.dragging = false;
    const topLeft = this.dragTopLeft;
    this.dragOrigin = null;
    this.dragTopLeft = null;
    if (this.mode === "poll" || !topLeft) return;
    void Bridge.desktopDragEnd(Math.round(topLeft.x), Math.round(topLeft.y));
  }

  // ── The other Mochis around him ─────────────────────────────────────────────

  private openFan() {
    // Nobody else to bring out: the island opens at the top instead.
    if (this.snap.others.length === 0) {
      void emitToWindow(ISLAND, DESKTOP_EVENTS.open);
      return;
    }
    void Bridge.desktopFan(true).then((fan) => {
      // His window can't grow here (layer shell): the island opens at the top.
      if (!fan) {
        void emitToWindow(ISLAND, DESKTOP_EVENTS.open);
        return;
      }
      this.fan = fan;
      this.setOffset({ x: fan.x, y: fan.y });
      this.fanKey = "";
      this.drawFan();
      Sound.play("pop");
      this.keepFan();
      this.schedule();
    });
  }

  /** Puts them away; `tellRust` shrinks the window back (Rust may have done it). */
  private closeFan(tellRust: boolean) {
    if (this.fanIdle != null) window.clearTimeout(this.fanIdle);
    this.fanIdle = null;
    if (!this.fan) return;
    this.fan = null;
    this.fanKey = "";
    this.fanSlot = null;
    this.fanEl.replaceChildren();
    pruneMiniBots();
    if (!this.dragging) this.setOffset({ x: 0, y: 0 });
    if (tellRust) void Bridge.desktopFan(false);
  }

  /** Something happened near them: they stay out a while longer. */
  private keepFan() {
    if (this.fanIdle != null) window.clearTimeout(this.fanIdle);
    this.fanIdle = window.setTimeout(() => {
      this.fanIdle = null;
      this.closeFan(true);
    }, FAN_IDLE_MS);
  }

  /** Lays them out on the half circle; rebuilt only when the pills change. */
  private drawFan() {
    const fan = this.fan;
    if (!fan) return;
    const others = this.snap.others;
    this.fanIds = fanOrder(this.fanIds, others.map((o) => o.id), this.fanSlot);
    this.fanSlot = null;
    const shown = this.fanIds.map((id) => others.find((o) => o.id === id)).filter((o): o is FanMochi => !!o);
    const key = `${fan.x},${fan.y},${fan.right}|${shown.map((o) => `${o.id}:${o.color}`).join(",")}`;
    if (key === this.fanKey) {
      syncMiniBotStates(shown as AgentTask[]);
      return;
    }
    this.fanKey = key;
    this.fanEl.replaceChildren();
    pruneMiniBots();
    const center = { x: fan.x + PANEL_SIZE / 2, y: fan.y + PANEL_SIZE / 2 };
    const points = fanPoints(shown.length, center, fan.right);
    shown.forEach((o, i) => {
      const p = points[i];
      const el = document.createElement("div");
      el.className = "fan-mini";
      el.title = o.name;
      el.style.left = `${p.x - FAN_BODY / 2}px`;
      el.style.top = `${p.y - FAN_BODY / 2}px`;
      el.append(createMiniBot(o as AgentTask, FAN_BODY));
      el.addEventListener("click", () => this.swap(o.id, i, el, p, center));
      this.fanEl.append(el);
    });
  }

  /**
   * A click on one of them: it flies into him and grows, then its pill comes
   * into focus; the one that was in focus takes the place it left.
   */
  private swap(id: string, slot: number, el: HTMLElement, from: Point, to: Point) {
    if (el.classList.contains("leaving")) return;
    this.keepFan();
    el.classList.add("leaving");
    el.style.animation = "none";
    el.style.transform = `translate(${to.x - from.x}px, ${to.y - from.y}px) scale(2.4)`;
    el.style.opacity = "0";
    window.setTimeout(() => {
      if (!this.fan) return;
      this.fanSlot = slot;
      this.engine.blink();
      Sound.play("pop");
      void emitToWindow(ISLAND, DESKTOP_EVENTS.focus, id);
    }, SWAP_MS);
  }

  private setOffset(p: Point) {
    this.offset = p;
    this.canvas.style.left = `${p.x}px`;
    this.canvas.style.top = `${p.y}px`;
  }
}

const canvas = document.getElementById("mochi");
const fanEl = document.getElementById("fan");
if (canvas instanceof HTMLCanvasElement && fanEl) void new DesktopMochi(canvas, fanEl).start();
