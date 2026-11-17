'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { mask, functions, imports, diagnostic } = require('../language');

test('mask preserves offsets and hides nested comments and escaped strings', () => {
    const source = '/* outer /* func fake() {} */ end */\nfunc real() { say("func false() \\\" ") }';
    assert.equal(mask(source).length, source.length);
    assert.deepEqual(functions(source).map(f => f.name), ['real']);
});
test('typed multiline function completions', () => {
    assert.deepEqual(functions('func sum(\na: int,\nb: float\n) -> float { give a + b }'),
        [{ name: 'sum', params: ['a: int', 'b: float'], result: 'float', offset: 0 }]);
});
test('imports parse bare names, aliases, paths and ignore comments', () => {
    assert.deepEqual(imports('use math\nuse math as m\nuse "lib/text.fc" as text\n// use fake'),
        [{ alias: 'math', path: 'math.fc', named: true }, { alias: 'm', path: 'math.fc', named: true }, { alias: 'text', path: 'lib/text.fc', named: false }]);
});
test('diagnostics support Windows paths and convert positions', () => {
    assert.deepEqual(diagnostic('C:\\my folder\\main.fc:3:5: error: Unknown variable\r\n  foo\r\n  ^'),
        { file: 'C:\\my folder\\main.fc', line: 2, column: 4, message: 'Unknown variable' });
    assert.equal(diagnostic('OK: main.fc'), null);
});
test('import-looking strings do not create module suggestions', () => {
    assert.deepEqual(imports('func main() { say("use fake") }\nuse real'), [{ alias: 'real', path: 'real.fc', named: true }]);
});
test('number and container return types are preserved in signatures', () => {
    assert.deepEqual(functions('func square(x: number) -> number {} func values() -> list {} func object() -> map {} func value() -> any {}').map(f => f.result),
        ['number', 'list', 'map', 'any']);
});
