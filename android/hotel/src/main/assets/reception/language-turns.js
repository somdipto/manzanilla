// Generated from ui/reception/language-turns.js; run npm run build:reception.
(function(root) {
  "use strict";
  class LanguageTurns {
    constructor() {
      this.turns = [];
      this.byId = /* @__PURE__ */ new Map();
    }
    clear() {
      this.turns.length = 0;
      this.byId.clear();
    }
    settle(sessionId, generation) {
      const changed = [];
      for (const turn of this.turns) {
        if (turn.sessionId !== sessionId || turn.generation !== generation || turn.localClosed) continue;
        turn.localClosed = true;
        if (["waiting", "needs_direction", "streaming"].includes(turn.status)) turn.status = "cancelled";
        if (["queued", "playing"].includes(turn.playbackStatus)) turn.playbackStatus = "interrupted";
        changed.push(turn);
      }
      return changed;
    }
    accept(event, guest, staff) {
      const record = event && event.type === "translation.utterance" && event.record;
      if (!record || typeof record.session_id !== "string" || record.session_id !== event.session_id || !Number.isInteger(event.generation) || event.generation < 1 || typeof record.utterance_id !== "string" || !record.utterance_id || !Number.isInteger(record.revision) || record.revision < 1 || typeof record.original !== "string" || typeof record.translation !== "string" || !["partial", "final", "degraded"].includes(record.recognition_status) || !["waiting", "needs_direction", "streaming", "complete", "cancelled", "error", "ignored"].includes(record.translation_status) || !["idle", "queued", "playing", "complete", "interrupted", "error"].includes(record.playback_status) || !["recognized", "uncertain", "mixed", "mismatch", "same_language", "manual"].includes(record.direction_reason) || ![null, "guest", "receptionist"].includes(record.role)) return [];
      const id = JSON.stringify([record.session_id, event.generation, record.utterance_id]);
      const existing = this.byId.get(id);
      if (existing && (existing.localClosed || record.revision <= existing.revision || existing.recognitionStatus !== "partial" && record.original !== existing.original)) return [];
      const source = record.source_language;
      const target = record.target_language;
      let side = null;
      if (record.role !== null) {
        const from = record.role === "guest" ? guest : staff;
        const to = record.role === "guest" ? staff : guest;
        if (record.recognition_status === "partial" || source !== from || target !== to || !["recognized", "manual"].includes(record.direction_reason) || record.direction_reason === "recognized" && (guest === staff || record.recognition_status !== "final" || record.detected_language !== source)) return [];
        side = record.role === "guest" ? "guest" : "staff";
      }
      const turn = existing || { id, kind: "human", node: null };
      Object.assign(turn, {
        sessionId: record.session_id,
        generation: event.generation,
        utteranceId: record.utterance_id,
        revision: record.revision,
        side,
        sourceLanguage: source,
        targetLanguage: target,
        detectedLanguage: record.detected_language,
        original: record.original,
        text: record.translation,
        status: record.translation_status,
        recognitionStatus: record.recognition_status,
        playbackStatus: record.playback_status,
        directionReason: record.direction_reason,
        final: { original: record.recognition_status !== "partial", text: record.translation_status === "complete" }
      });
      if (!existing) {
        this.byId.set(id, turn);
        this.turns.push(turn);
      }
      return [turn];
    }
  }
  if (typeof module === "object" && module.exports) module.exports = { LanguageTurns };
  else root.ManzanillaLanguageTurns = LanguageTurns;
})(typeof window === "object" ? window : globalThis);
