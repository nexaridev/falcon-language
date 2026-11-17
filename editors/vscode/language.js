'use strict';

// Keep offsets intact while removing comments/strings from symbol searches.
function mask(source, strings = true) {
    let result = '', i = 0, depth = 0, quoted = false;
    while (i < source.length) {
        const c = source[i], pair = source.slice(i, i + 2);
        if (depth) {
            if (pair === '/*') { depth++; result += '  '; i += 2; }
            else if (pair === '*/') { depth--; result += '  '; i += 2; }
            else { result += c === '\n' ? '\n' : ' '; i++; }
        } else if (quoted) {
            if (c === '\\' && i + 1 < source.length) {
                result += strings ? '  ' : source.slice(i, i + 2); i += 2;
            } else { result += strings && c !== '\n' ? ' ' : c; i++; if (c === '"' || c === '\n') quoted = false; }
        } else if (pair === '//') {
            while (i < source.length && source[i] !== '\n') { result += ' '; i++; }
        } else if (pair === '/*') { depth = 1; result += '  '; i += 2; }
        else if (c === '"') { quoted = true; result += strings ? ' ' : c; i++; }
        else { result += c; i++; }
    }
    return result;
}

const identifier = '[\\p{L}_][\\p{L}\\p{N}_]*';
function functions(source) {
    const pattern = new RegExp(`\\bfunc\\s+(${identifier})\\s*\\(([^)]*)\\)\\s*(?:->\\s*(int|float|number|bool|string|void|any|list|map)\\b)?`, 'gu');
    return [...mask(source).matchAll(pattern)].map(m => ({
        name: m[1], params: m[2].split(',').map(p => p.trim()).filter(Boolean), result: m[3] || 'void', offset: m.index
    }));
}

function imports(source) {
    const pattern = new RegExp(`\\buse\\s+(?:"((?:\\\\.|[^"\\r\\n])*)"\\s+as\\s+(${identifier})|(${identifier})(?:[ \\t]+as[ \\t]+(${identifier}))?)`, 'gu');
    const code = mask(source);
    return [...mask(source, false).matchAll(pattern)].filter(m => code.slice(m.index, m.index + 3) === 'use').map(m => ({
        alias: m[2] || m[4] || m[3],
        path: m[1] === undefined ? `${m[3]}.fc` : m[1].replace(/\\([nrt"\\])/g, (_, c) => ({ n: '\n', r: '\r', t: '\t' }[c] || c)),
        named: m[1] === undefined
    }));
}

function diagnostic(output) {
    const match = /^(.*):(\d+):(\d+): error: (.*)$/m.exec(output.replace(/\r/g, ''));
    return match ? { file: match[1], line: Number(match[2]) - 1, column: Number(match[3]) - 1, message: match[4] } : null;
}

module.exports = { mask, functions, imports, diagnostic, identifier };
