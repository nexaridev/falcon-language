// Run after building: node scripts/test-unicode.cjs (Windows launcher integration).
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');
const root = path.resolve(__dirname, '..');
const folder = fs.mkdtempSync(path.join(os.tmpdir(), 'falcon-unicode-'));
const file = path.join(folder, 'кирилиця.fc');
const value = 'Мой господин — Привіт, Україно! ї є ґ 😀 中文';
try {
    fs.writeFileSync(file, 'use io\nfunc main() { say("Привіт!")\nsay("Hello " + io.readLine()) }', 'utf8');
    const run = args => spawnSync('powershell.exe', ['-NoLogo', '-NoProfile', '-ExecutionPolicy', 'Bypass',
        '-File', path.join(root, 'falcon.ps1'), ...args], {
        cwd: root, input: value + '\n', encoding: 'utf8', timeout: 30000, windowsHide: true
    });
    const result = run(['run', file]);
    assert.equal(result.status, 0, result.stderr || String(result.error));
    assert.equal(result.stdout.replaceAll('\r\n', '\n'), 'Привіт!\nHello ' + value + '\n');
    fs.writeFileSync(file, 'func main() { say(unknown) }', 'utf8');
    const error = run(['check', file]);
    assert.equal(error.status, 1);
    assert.ok(error.stderr.includes(file), error.stderr);
    assert.ok(error.stderr.includes('Unknown variable'), error.stderr);
    console.log('PASS: launcher Unicode source, input, output, diagnostic paths, and exit codes.');
} finally {
    fs.unlinkSync(file);
    fs.rmdirSync(folder);
}
