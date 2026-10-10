// Settings → Android phone: the switch for the phone link and the code that
// pairs a phone with this computer. Off until the user turns it on.

import { Bridge, type PhoneChatStatus, type PhoneLinkPairing, type PhoneLinkStatus } from "../core/bridge";
import { modelId, modelsOf, toggleModel, usableProviders } from "../core/phone-chat";
import { PROVIDERS, type ProviderDef } from "../core/providers";
import type { Settings } from "../core/state";
import { h } from "../views/dom";
import { t } from "../i18n/i18n";

type Toggle = (on: boolean, onChange: (v: boolean) => void) => HTMLElement;

export function phoneSection(on: boolean, makeToggle: Toggle, settings: Settings): HTMLElement {
  const detail = h("div", {});
  const note = h("div", { class: "hint" });
  /** Where the pairing code is drawn, so showing it never hides the switches below. */
  const pairingBox = h("div", {});

  const showError = (message: string) => {
    note.textContent = t("Could not turn it on: {error}", { error: message });
  };

  /** The code itself, which lets a phone in: shown only when asked for. */
  const reveal = async (status: PhoneLinkStatus, newCode: boolean) => {
    try {
      const pairing = newCode ? await Bridge.phoneLinkNewPairing() : await Bridge.phoneLinkPairing();
      pairingBox.replaceChildren(...pairingRows(pairing, () => void reveal(status, true)));
    } catch (err) {
      showError(String(err));
    }
  };

  /** Draws what is below the switch from the link's state. */
  const draw = (status: PhoneLinkStatus | null) => {
    detail.replaceChildren();
    pairingBox.replaceChildren();
    note.textContent = "";
    if (status?.error) showError(status.error);
    if (!status?.running) return;
    const show = h("button", { text: t("Show pairing code") });
    show.addEventListener("click", () => void reveal(status, false));
    const address = status.host ? `${status.host}:${status.port}` : `:${status.port}`;
    clients.textContent = t("Phones connected: {count}", { count: status.clients });
    detail.append(
      h("div", { class: "row" }, show),
      pairingBox,
      clients,
      h("div", { class: "hint", text: t("The phone connects to {address}", { address }) }),
      h("div", { class: "hint", text: t("If Windows asks about the firewall, allow Coucou on private networks. The phone and this computer must be on the same Wi-Fi.") }),
      h("div", { class: "hint", text: t("Your phone finds this computer again by itself when the network changes. It is announced on your local network only while this switch is on. Some hotspots and guest networks block this.") }),
      detailsBlock(makeToggle),
      chatBlock(makeToggle, settings),
    );
  };

  /** Kept up to date while the window is open and the link is on; nothing runs otherwise. */
  const clients = h("div", { class: "hint" });
  const watch = window.setInterval(() => {
    if (!section.isConnected) return window.clearInterval(watch);
    if (document.visibilityState !== "visible" || !clients.isConnected) return;
    void Bridge.phoneLinkStatus().then((s) => {
      if (s?.running) clients.textContent = t("Phones connected: {count}", { count: s.clients });
    });
  }, 5000);

  const switchEl = makeToggle(on, (next) => {
    void (async () => {
      try {
        draw(await Bridge.phoneLinkSetEnabled(next));
      } catch (err) {
        // It could not start: the switch goes back off and says why.
        switchEl.classList.remove("on");
        switchEl.setAttribute("aria-pressed", "false");
        detail.replaceChildren();
        showError(String(err));
      }
    })();
  });

  void Bridge.phoneLinkStatus().then(draw);

  const section = h(
    "section",
    {},
    h("h2", {}, h("span", { text: t("Android phone") })),
    h("div", { class: "hint", text: t("Send your agents' sessions to Coucou for Android and answer permission requests from the phone. Off by default; it only listens on your local network.") }),
    h("div", { class: "hint", text: t("Allow on the phone asks for the fingerprint or the screen lock. Deny does not.") }),
    h("div", { class: "row" }, h("label", { text: t("Phone link") }), switchEl),
    note,
    detail,
  );
  return section;
}

/**
 * Session details for the phone (the steps, the last line, the folder name, the colour): off until the
 * user turns it on. The phone is told only if it asks; Rust re-reads this switch for every connection.
 */
function detailsBlock(makeToggle: Toggle): HTMLElement {
  const note = h("div", { class: "hint" });
  const switchEl = makeToggle(false, (next) => {
    void (async () => {
      note.textContent = "";
      try {
        await Bridge.phoneDetailsSetEnabled(next);
      } catch (err) {
        switchEl.classList.remove("on");
        switchEl.setAttribute("aria-pressed", "false");
        note.textContent = String(err);
      }
    })();
  });
  // The switch shows what Rust says, not what the page remembers.
  void Bridge.phoneDetailsStatus().then((status) => {
    if (!status) return;
    switchEl.classList.toggle("on", status.enabled);
    switchEl.setAttribute("aria-pressed", String(status.enabled));
  });
  return h(
    "div",
    {},
    h("div", { class: "row" }, h("label", { text: t("Show session details on the phone") }), switchEl),
    h("div", { class: "hint", text: t("Sends the last steps (as shown on the island, so they can include commands), the final line, the project's folder name and the colour. Never a path.") }),
    note,
  );
}

/**
 * Chat from the phone: off until the user turns it on, and then only the models ticked here.
 * The phone gets text; the keys stay on this computer (src-tauri/src/phone_link/chat.rs).
 */
function chatBlock(makeToggle: Toggle, settings: Settings): HTMLElement {
  let allowed: string[] = [];
  const body = h("div", {});
  const note = h("div", { class: "hint" });

  const save = async (next: string[]) => {
    try {
      allowed = (await Bridge.phoneChatSetModels(next)).models;
    } catch (err) {
      note.textContent = String(err);
    }
    emptyNote.hidden = allowed.length > 0;
  };
  const emptyNote = h("div", { class: "hint", text: t("Nothing is allowed yet: the phone cannot chat until you tick a model.") });

  /** One provider: a button that asks it for its models (only now), then a tick box per model. */
  const providerRow = (p: ProviderDef): HTMLElement => {
    const list = h("div", {});
    const button = h("button", { text: t("Choose models") });
    const head = h("div", { class: "row" }, h("label", { text: p.name }), button);
    const show = (models: string[]) => {
      list.replaceChildren(
        ...[...new Set([...models, ...modelsOf(allowed, p.id)])].map((model) => {
          const id = modelId(p.id, model);
          const box = h("input", { type: "checkbox" }) as HTMLInputElement;
          box.checked = allowed.includes(id);
          box.addEventListener("change", () => void save(toggleModel(allowed, id, box.checked)));
          return h("div", { class: "row" }, h("label", {}, box, h("span", { text: ` ${model}` })));
        }),
      );
    };
    button.addEventListener("click", () => {
      list.replaceChildren(h("div", { class: "hint", text: t("Loading models…") }));
      Bridge.chatModels(p.id).then(
        (models) => show(models.map((m) => m.id)),
        (err) => list.replaceChildren(h("div", { class: "hint", text: String(err).replace(/^Error:\s*/, "") })),
      );
    });
    return h("div", {}, head, list);
  };

  const draw = async (status: PhoneChatStatus) => {
    allowed = status.models;
    body.replaceChildren();
    if (!status.enabled) return;
    const hasKey: Record<string, boolean> = {};
    for (const p of usableProvidersCandidates()) hasKey[p.id] = (await Bridge.secretPresent(p.key!)) ?? false;
    const providers = usableProviders(settings, hasKey);
    body.append(h("div", { class: "hint", text: t("Models the phone may use") }));
    if (providers.length === 0) body.append(h("div", { class: "hint", text: t("No provider is ready: add an API key or connect a local model first.") }));
    for (const p of providers) body.append(providerRow(p));
    emptyNote.hidden = allowed.length > 0;
    body.append(emptyNote);
  };

  const switchEl = makeToggle(false, (next) => {
    void (async () => {
      note.textContent = "";
      try {
        await draw(await Bridge.phoneChatSetEnabled(next));
      } catch (err) {
        switchEl.classList.remove("on");
        switchEl.setAttribute("aria-pressed", "false");
        body.replaceChildren();
        note.textContent = String(err);
      }
    })();
  });
  // The switch shows what Rust says, not what the page remembers.
  void Bridge.phoneChatStatus().then((status) => {
    if (!status) return;
    switchEl.classList.toggle("on", status.enabled);
    switchEl.setAttribute("aria-pressed", String(status.enabled));
    void draw(status);
  });

  return h(
    "div",
    {},
    h("div", { class: "row" }, h("label", { text: t("Let the phone chat with my AI providers") }), switchEl),
    h("div", { class: "hint", text: t("Chat uses the API keys saved on this computer. The phone never sees them, and each message may cost money.") }),
    note,
    body,
  );
}

function usableProvidersCandidates(): ProviderDef[] {
  return PROVIDERS.filter((p) => p.key !== null);
}

function pairingRows(pairing: PhoneLinkPairing, again: () => void): HTMLElement[] {
  // The QR comes from Rust (qrcode crate) as an SVG made only of paths.
  const qr = h("div", { class: "qr" });
  qr.innerHTML = pairing.qrSvg;

  const link = h("input", { type: "text", readonly: "true", value: pairing.link, style: "width:100%" }) as HTMLInputElement;
  link.addEventListener("focus", () => link.select());

  const copy = h("button", { text: t("Copy link") });
  copy.addEventListener("click", () => {
    void navigator.clipboard?.writeText(pairing.link).then(() => {
      copy.textContent = t("Copied");
      window.setTimeout(() => (copy.textContent = t("Copy link")), 1500);
    });
  });

  const repair = h("button", { text: t("Pair again") });
  repair.addEventListener("click", again);

  return [
    h("div", { class: "hint", text: t("In Coucou for Android, scan this code with the camera or paste the link.") }),
    qr,
    h("div", { class: "row" }, link, copy),
    h("div", { class: "row" }, repair, h("span", { class: "hint", text: t("The phone that was paired is disconnected and needs the new code.") })),
  ];
}
