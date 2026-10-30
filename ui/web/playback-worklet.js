// The only translated-audio renderer. Credit is emitted by process(), never enqueue.
class TranslationPlayback extends AudioWorkletProcessor {
  constructor() {
    super();
    this.samples = new Float32Array(24000);
    this.ends = new Float64Array(64);
    this.sequences = new Uint32Array(64);
    this.generation = 0;
    this.closed = true;
    this.clear();
    this.port.onmessage = ({ data }) => this.receive(data);
  }

  clear() {
    this.written = 0;
    this.consumed = 0;
    this.markerRead = 0;
    this.markerCount = 0;
    this.nextSequence = 0;
    this.eof = false;
  }

  fail() {
    if (this.closed) return;
    this.closed = true;
    this.clear();
    this.port.postMessage({ type: 'error', generation: this.generation });
  }

  receive(message) {
    if (!message || typeof message !== 'object') return;
    if (message.type === 'begin') {
      if (!Number.isInteger(message.generation) || message.generation <= this.generation || message.generation > 0xffffffff) return;
      this.clear();
      this.generation = message.generation;
      this.closed = false;
      return;
    }
    if (message.generation !== this.generation || this.closed) return;
    if (message.type === 'flush') {
      this.closed = true;
      this.clear();
      return;
    }
    if (message.type !== 'pcm') return;
    const bytes = message.bytes;
    if (!(bytes instanceof Uint8Array) || bytes.byteLength < 16 || bytes.byteLength > 1936 || bytes.byteLength % 2) { this.fail(); return; }
    const header = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
    const sequence = header.getUint32(8);
    if (header.getUint32(0) !== 0x4d5a5031 || header.getUint32(4) !== this.generation ||
        header.getUint32(12) !== 24000 || sequence !== this.nextSequence || this.eof) { this.fail(); return; }
    const count = (bytes.byteLength - 16) / 2;
    if (this.written - this.consumed + count > this.samples.length || this.markerCount === this.ends.length) { this.fail(); return; }
    for (let i = 0; i < count; i++) this.samples[(this.written + i) % this.samples.length] = header.getInt16(16 + i * 2, true) / 32768;
    this.written += count;
    const marker = (this.markerRead + this.markerCount) % this.ends.length;
    this.ends[marker] = this.written;
    this.sequences[marker] = sequence;
    this.markerCount++;
    this.nextSequence++;
    this.eof = count === 0;
  }

  process(_inputs, outputs) {
    const channel = outputs[0]?.[0];
    if (!channel) return true;
    channel.fill(0);
    if (this.closed) return true;
    const count = Math.min(channel.length, this.written - this.consumed);
    for (let i = 0; i < count; i++) channel[i] = this.samples[(this.consumed + i) % this.samples.length];
    this.consumed += count;
    // A marker is acknowledged only after its last sample was copied to this
    // render quantum. EOF shares the prior end position and follows its ACK.
    while (this.markerCount && this.ends[this.markerRead] <= this.consumed) {
      const sequence = this.sequences[this.markerRead];
      const eof = this.eof && sequence === this.nextSequence - 1;
      this.port.postMessage({ type: 'played', generation: this.generation, sequence, eof });
      this.markerRead = (this.markerRead + 1) % this.ends.length;
      this.markerCount--;
      if (eof) this.closed = true;
    }
    return true;
  }
}

registerProcessor('translation-playback', TranslationPlayback);
