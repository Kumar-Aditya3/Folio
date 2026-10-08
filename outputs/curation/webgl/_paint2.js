  const M = FOLIO_MODEL.MODEL, a = f.atmos, sp = f.spec, dark = a.isDark;
  // `tint=0` renders the same geometry, alpha and lighting with GLASS_TINT forced to zero.
  // That is the untinted control the GCC metric compares against -- a real second render, not
  // an approximation of one, so the difference isolates what the *material* contributes.
  const TINT_ON = new URLSearchParams(location.search).get("tint") !== "0";

  // ── the crown and the capsule ──────────────────────────────────────────────
  // `FolioTopBar`'s own layers, with the glass body tinted. Alpha is `topBarFill`'s and is not
  // touched: colour strength comes from GLASS_TINT and GLASS_CHROMA_BOOST, because raising the
  // alpha to gain colour also destroys the translucency that makes it glass.
  (function paintChrome() {
    const m = f.materials;
    const fill = FOLIO_MODEL.topBarFill(1.0, true);
    const g = FOLIO_MODEL.GLASS[dark ? "dark" : "light"];
    const base = a.fieldTop;                 // the field the glass sits over
    const accent = f.colors.primaryAccent;   // the crown's family
    const rgba = function (c, alpha) {
      return "rgba(" + Math.round(c[0] * 255) + "," + Math.round(c[1] * 255) + "," +
             Math.round(c[2] * 255) + "," + alpha.toFixed(4) + ")";
    };

    const crown = document.querySelector(".crown");
    if (crown) {
      // Spatial variation: tau rises toward the lower edge, and the sheen adds a whisper at the
      // top, so the pane reads as a coloured optical material rather than a flat rectangle.
      const stops = [
        [fill.crown, g.tau + g.tauSheen, "0%"],
        [fill.waist, g.tau + g.tauEdge * 0.5, "60%"],
        [fill.foot, g.tau + g.tauEdge, "100%"]];
      crown.querySelector(".fill").style.background = "linear-gradient(180deg," +
        stops.map(function (s) {
          const tau = TINT_ON ? s[1] : 0;
          const glass = FOLIO_MODEL.tintGlass(base, accent, tau, g.k, g.lambda);
          return rgba(glass, s[0]) + " " + s[2];
        }).join(",") + ")";

      // The status scrim, the sheen, the foot fade and the hairline are unchanged.
      const sa = m.barScrim[3] * fill.scrim;
      const grad = function (list) {
        return "linear-gradient(180deg," + list.map(function (s) {
          return rgba(s[0], s[1]) + " " + s[2];
        }).join(",") + ")";
      };
      crown.querySelector(".scrim").style.background = grad([
        [m.barScrim, sa, "0%"], [m.barScrim, sa * 0.55, "45%"], [m.barScrim, sa * 0.12, "100%"]]);
      const sheenA = m.rimLight[3] * 0.30 * fill.presence;
      crown.querySelector(".sheen").style.background = grad([
        [m.rimLight, sheenA, "0%"], [m.rimLight, 0, "38%"]]);
      crown.querySelector(".footfade").style.background = grad([
        [m.barGlass, 0, "0%"], [m.barGlass, fill.crown * 0.45, "100%"]]);
      crown.querySelector(".hairline").style.background =
        "linear-gradient(90deg," + rgba(m.hairline, 0.55 * fill.presence) + " 0%," +
        rgba(m.hairline, 0.10 * fill.presence) + " 100%)";
      // The chromatic core stays. The glass gives the crown its material colour; the core gives
      // it the brand accent. Removing the core must not be how the body passes.
      const core = crown.querySelector(".core");
      if (core) {
        core.style.background =
          "linear-gradient(90deg," + rgba(accent, 0.62) + " 0%," + rgba(accent, 0.62) + " 72%," +
          rgba(accent, 0.16) + " 100%)";
      }
    }
    // The capsule's glass is tinted on the same principle.
    const cap = document.querySelector(".capsule");
    if (cap) {
      const navTau = TINT_ON ? g.tau * 0.6 : 0;
      cap.querySelector(".fill").style.background = rgba(
        FOLIO_MODEL.tintGlass(a.fieldBottom, f.colors.navigationSelected, navTau, g.k, g.lambda),
        FOLIO_MODEL.navCapsuleFill(true));
    }
  })();
