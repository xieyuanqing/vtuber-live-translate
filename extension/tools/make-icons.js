/**
 * 生成扩展图标（蓝底 + 两条白色字幕条）。没有图形工具也能改配色和形状：
 *
 *   node tools/make-icons.js
 */
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

const BG = [31, 111, 235]; // #1f6feb
const BAR = [255, 255, 255];

const CRC_TABLE = (() => {
  const t = new Int32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    t[n] = c;
  }
  return t;
})();

function crc32(buf) {
  let c = -1;
  for (let i = 0; i < buf.length; i++) c = CRC_TABLE[(c ^ buf[i]) & 0xff] ^ (c >>> 8);
  return (c ^ -1) >>> 0;
}

function chunk(type, data) {
  const len = Buffer.alloc(4);
  len.writeUInt32BE(data.length);
  const body = Buffer.concat([Buffer.from(type, 'ascii'), data]);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(body));
  return Buffer.concat([len, body, crc]);
}

/** 圆角矩形覆盖率，边缘做 2x2 超采样抗锯齿。 */
function coverage(x, y, size, radius) {
  let hits = 0;
  for (const dx of [0.25, 0.75]) {
    for (const dy of [0.25, 0.75]) {
      const px = x + dx;
      const py = y + dy;
      const cx = Math.min(Math.max(px, radius), size - radius);
      const cy = Math.min(Math.max(py, radius), size - radius);
      if ((px - cx) ** 2 + (py - cy) ** 2 <= radius * radius) hits++;
    }
  }
  return hits / 4;
}

function render(size) {
  const radius = size * 0.22;
  const barH = Math.max(2, Math.round(size * 0.11));
  const bars = [
    { y0: size * 0.44, x0: size * 0.2, x1: size * 0.8 },
    { y0: size * 0.66, x0: size * 0.28, x1: size * 0.72 },
  ];

  const raw = Buffer.alloc((size * 4 + 1) * size);
  let p = 0;
  for (let y = 0; y < size; y++) {
    raw[p++] = 0; // filter: none
    for (let x = 0; x < size; x++) {
      const a = coverage(x, y, size, radius);
      let color = BG;
      for (const b of bars) {
        if (y + 0.5 >= b.y0 && y + 0.5 < b.y0 + barH && x + 0.5 >= b.x0 && x + 0.5 < b.x1) {
          color = BAR;
        }
      }
      raw[p++] = color[0];
      raw[p++] = color[1];
      raw[p++] = color[2];
      raw[p++] = Math.round(a * 255);
    }
  }

  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(size, 0);
  ihdr.writeUInt32BE(size, 4);
  ihdr[8] = 8; // bit depth
  ihdr[9] = 6; // RGBA
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr),
    chunk('IDAT', zlib.deflateSync(raw, { level: 9 })),
    chunk('IEND', Buffer.alloc(0)),
  ]);
}

const outDir = path.join(__dirname, '..', 'icons');
fs.mkdirSync(outDir, { recursive: true });
for (const size of [16, 32, 48, 128]) {
  const file = path.join(outDir, `icon${size}.png`);
  fs.writeFileSync(file, render(size));
  console.log(`写入 ${path.relative(path.join(__dirname, '..'), file)}`);
}
