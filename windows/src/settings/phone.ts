// Settings → Android phone: the switch for the phone link and the code that
// pairs a phone with this computer. Off until the user turns it on.

import { Bridge, type PhoneLinkPairing, type PhoneLinkStatus } from "../core/bridge";
import { h } from "../views/dom";
import { t } from "../i18n/i18n";

type Toggle = (on: boolean, onChange: (v: boolean) => void) => HTMLElement;

export function phoneSection(on: boolean, makeToggle: Toggle): HTMLElement {
  const detail = h("div", {});
  const note = h("div", { class: "hint" });

  const showError = (message: string) => {
    note.textContent = t("Could not turn it on: {error}", { error: message });
  };

  /** The code itself, which lets a phone in: shown only when asked for. */
  const reveal = async (status: PhoneLinkStatus, newCode: boolean) => {
    try {
      const pairing = newCode ? await Bridge.phoneLinkNewPairing() : await Bridge.phoneLinkPairing();
      detail.replaceChildren(...pairingRows(pairing, () => void reveal(status, true)));
    } catch (err) {
      showError(String(err));
    }
  };

  /** Draws what is below the switch from the link's state. */
  const draw = (status: PhoneLinkStatus | null) => {
    detail.replaceChildren();
    note.textContent = "";
    if (status?.error) showError(status.error);
    if (!status?.running) return;
    const show = h("button", { text: t("Show pairing code") });
    show.addEventListener("click", () => void reveal(status, false));
    detail.append(
      h("div", { class: "row" }, show),
      h("div", { class: "hint", text: t("Phones connected: {count}", { count: status.clients }) }),
    );
  };

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

  return h(
    "section",
    {},
    h("h2", {}, h("span", { text: t("Android phone") })),
    h("div", { class: "hint", text: t("Send your agents' sessions to Coucou for Android and answer permission requests from the phone. Off by default; it only listens on your local network.") }),
    h("div", { class: "hint", text: t("Allow on the phone asks for the fingerprint or the screen lock. Deny does not.") }),
    h("div", { class: "row" }, h("label", { text: t("Phone link") }), switchEl),
    note,
    detail,
  );
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
