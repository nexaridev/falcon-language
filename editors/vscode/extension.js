'use strict';

const vscode = require('vscode');
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const { execFile } = require('node:child_process');
const language = require('./language');

function execute(file, args, options = {}, input) {
    let child;
    const promise = new Promise((resolve, reject) => {
        child = execFile(file, args, { windowsHide: true, timeout: 10000, maxBuffer: 1024 * 1024, ...options },
            (error, stdout, stderr) => {
                if (error && (typeof error.code !== 'number' || error.killed)) reject(error);
                else resolve({ code: error ? error.code : 0, stdout, stderr });
            });
        // An early launch failure may close stdin before writing finishes.
        child.stdin.on('error', () => {});
        child.stdin.end(input);
    });
    return { promise, cancel: () => child.kill() };
}

async function findJava(config) {
    const explicit = config.get('javaPath');
    const executable = process.platform === 'win32' ? 'java.exe' : 'java';
    const candidates = explicit ? [explicit] : [
        ...(process.env.JAVA_HOME ? [path.join(process.env.JAVA_HOME, 'bin', executable)] : []), executable
    ];
    if (!explicit) {
        const roots = [path.join(os.homedir(), '.jdks')];
        if (process.platform === 'win32') {
            if (process.env.LOCALAPPDATA) roots.push(path.join(process.env.LOCALAPPDATA, 'Programs', 'Eclipse Adoptium'));
            if (process.env.ProgramFiles) roots.push(path.join(process.env.ProgramFiles, 'Eclipse Adoptium'), path.join(process.env.ProgramFiles, 'Java'));
        }
        for (const root of roots) {
            try {
                for (const item of (await fs.promises.readdir(root, { withFileTypes: true })).reverse())
                    if (item.isDirectory()) candidates.push(path.join(root, item.name, 'bin', executable));
            } catch { /* Optional installation directory. */ }
        }
        if (process.platform === 'win32') {
            try {
                const adoptium = path.join(process.env.LOCALAPPDATA || '', 'Programs', 'Eclipse Adoptium');
                for (const entry of await fs.promises.readdir(adoptium, { withFileTypes: true }))
                    if (entry.isDirectory()) candidates.unshift(path.join(adoptium, entry.name, 'bin', executable));
            } catch { /* Optional JDK folder. */ }
        }
    }
    for (const candidate of [...new Set(candidates)]) {
        try {
            const result = await execute(candidate, ['-version']).promise;
            const version = /version "(\d+)/.exec(result.stderr + result.stdout);
            if (result.code === 0 && version && Number(version[1]) >= 17) return candidate;
        } catch { /* Try the next runtime. */ }
    }
    throw new Error('Java 17+ was not found. Set falcon.javaPath to your java executable.');
}

function activate(context) {
    const selector = { language: 'falcon', scheme: 'file' };
    const diagnostics = vscode.languages.createDiagnosticCollection('falcon');
    const output = vscode.window.createOutputChannel('Falcon');
    const pending = new Map(), contributions = new Map();
    let javaPromise;
    context.subscriptions.push(diagnostics, output);

    function publish() {
        diagnostics.clear();
        const grouped = new Map();
        for (const entries of contributions.values()) {
            for (const [uri, diagnostic] of entries) {
                const key = uri.toString(), group = grouped.get(key) || { uri, items: [] };
                if (!group.items.some(d => d.message === diagnostic.message && d.range.isEqual(diagnostic.range))) group.items.push(diagnostic);
                grouped.set(key, group);
            }
        }
        for (const { uri, items } of grouped.values()) diagnostics.set(uri, items);
    }

    async function validate(document, state) {
        const key = document.uri.toString();
        if (pending.get(key) !== state || document.isClosed) return;
        const config = vscode.workspace.getConfiguration('falcon');
        if (!config.get('diagnostics.enabled', true)) { contributions.delete(key); publish(); return; }
        const source = document.getText();
        try {
            javaPromise ||= findJava(config);
            const java = await javaPromise;
            if (pending.get(key) !== state) return;
            const jar = config.get('compilerPath') || context.asAbsolutePath('runtime/falcon.jar');
            const standardPath = config.get('stdlibPath') || context.asAbsolutePath('stdlib');
            const task = execute(java, [`-Dfalcon.stdlib=${standardPath}`, '-jar', jar, 'check', document.uri.fsPath, '--stdin', '--library'], {}, source);
            state.cancel = task.cancel;
            const result = await task.promise;
            if (pending.get(key) !== state || document.isClosed) return;
            const error = language.diagnostic(result.stderr);
            if (error) {
                const uri = vscode.Uri.file(error.file);
                const position = new vscode.Position(Math.max(0, error.line), Math.max(0, error.column));
                const target = vscode.workspace.textDocuments.find(d => d.uri.toString() === uri.toString());
                const range = target?.getWordRangeAtPosition(position) || new vscode.Range(position, position.translate(0, 1));
                const diagnostic = new vscode.Diagnostic(range, error.message, vscode.DiagnosticSeverity.Error);
                diagnostic.source = 'Falcon';
                contributions.set(key, [[uri, diagnostic]]);
            } else if (result.code !== 0) throw new Error(result.stderr.trim() || `Compiler exited with code ${result.code}.`);
            else contributions.delete(key);
            publish();
        } catch (error) {
            if (pending.get(key) !== state || document.isClosed) return;
            javaPromise = undefined;
            output.appendLine(String(error));
            const diagnostic = new vscode.Diagnostic(new vscode.Range(0, 0, 0, 1),
                `Falcon could not check this file: ${error.message}`, vscode.DiagnosticSeverity.Warning);
            diagnostic.source = 'Falcon'; contributions.set(key, [[document.uri, diagnostic]]); publish();
        }
    }

    function schedule(document, delay = 250) {
        if (document.languageId !== 'falcon' || document.uri.scheme !== 'file') return;
        const key = document.uri.toString(), previous = pending.get(key);
        if (previous) { clearTimeout(previous.timer); previous.cancel?.(); }
        const state = {};
        pending.set(key, state);
        state.timer = setTimeout(() => validate(document, state), delay);
    }
    function scheduleAll() { for (const document of vscode.workspace.textDocuments) schedule(document); }

    context.subscriptions.push(
        vscode.workspace.onDidOpenTextDocument(document => schedule(document, 0)),
        vscode.workspace.onDidChangeTextDocument(event => { if (event.contentChanges.length) schedule(event.document); }),
        vscode.workspace.onDidSaveTextDocument(document => { if (document.languageId === 'falcon') scheduleAll(); }),
        vscode.workspace.onDidCloseTextDocument(document => {
            const key = document.uri.toString(), state = pending.get(key);
            if (state) { clearTimeout(state.timer); state.cancel?.(); }
            pending.delete(key); contributions.delete(key); publish();
        }),
        vscode.workspace.onDidChangeConfiguration(event => {
            if (event.affectsConfiguration('falcon')) { javaPromise = undefined; scheduleAll(); }
        }),
        vscode.commands.registerCommand('falcon.checkDocument', () => {
            const document = vscode.window.activeTextEditor?.document;
            if (document?.uri.scheme === 'untitled') return vscode.window.showInformationMessage('Save this file as .fc to check it.');
            if (document) schedule(document, 0);
        }),
        { dispose: () => { for (const state of pending.values()) { clearTimeout(state.timer); state.cancel?.(); } pending.clear(); } }
    );
    const watcher = vscode.workspace.createFileSystemWatcher('**/*.fc');
    context.subscriptions.push(watcher, watcher.onDidChange(scheduleAll), watcher.onDidCreate(scheduleAll), watcher.onDidDelete(scheduleAll));

    function functionItem(fn) {
        const item = new vscode.CompletionItem(fn.name, vscode.CompletionItemKind.Function);
        item.detail = `func ${fn.name}(${fn.params.join(', ')})${fn.result === 'void' ? '' : ` -> ${fn.result}`}`;
        const snippet = new vscode.SnippetString().appendText(fn.name + '(');
        fn.params.forEach((param, i) => { if (i) snippet.appendText(', '); snippet.appendPlaceholder(param.split(':')[0].trim()); });
        snippet.appendText(')').appendTabstop(0);
        item.insertText = snippet;
        return item;
    }
    async function readModule(document, alias) {
        const imported = language.imports(document.getText()).find(i => i.alias === alias);
        if (!imported) return '';
        const bundled = async filename => {
            const fullPath = path.join(vscode.workspace.getConfiguration('falcon').get('stdlibPath') || context.asAbsolutePath('stdlib'), filename);
            const open = vscode.workspace.textDocuments.find(d => path.resolve(d.uri.fsPath) === path.resolve(fullPath));
            if (open) return open.getText();
            try { return await fs.promises.readFile(fullPath, 'utf8'); } catch { return ''; }
        };
        const standard = new RegExp(`^std/(${language.identifier}\\.fc)$`, 'u').exec(imported.path);
        if (standard) return bundled(standard[1]);
        const filename = path.resolve(path.dirname(document.uri.fsPath), imported.path);
        const open = vscode.workspace.textDocuments.find(d => path.resolve(d.uri.fsPath) === filename);
        if (open) return open.getText();
        try { return await fs.promises.readFile(filename, 'utf8'); }
        catch (error) {
            if (error.code === 'ENOENT' && imported.named) return bundled(imported.path);
            return '';
        }
    }

    context.subscriptions.push(vscode.languages.registerCompletionItemProvider(selector, {
        async provideCompletionItems(document, position) {
            const before = document.getText(new vscode.Range(new vscode.Position(0, 0), position));
            // Ignore strings/comments, including unfinished strings and nested block comments.
            const clean = language.mask(before);
            if (before.length && clean.at(-1) !== before.at(-1) && before.at(-1) !== ' ') return [];
            const line = document.lineAt(position.line).text.slice(0, position.character);
            const member = new RegExp(`(${language.identifier})\\.([\\p{L}\\p{N}_]*)$`, 'u').exec(line);
            if (member) return language.functions(await readModule(document, member[1])).map(functionItem);
            const items = language.functions(document.getText()).map(functionItem);
            items.push(functionItem({ name: 'say', params: ['value'], result: 'void' }));
            for (const word of ['func', 'var', 'fix', 'give', 'if', 'elseif', 'else', 'when', 'while', 'each', 'in', 'use', 'as', 'true', 'false', 'null', 'int', 'float', 'number', 'bool', 'string', 'void', 'any', 'list', 'map'])
                items.push(new vscode.CompletionItem(word, vscode.CompletionItemKind.Keyword));
            for (const imported of language.imports(document.getText())) {
                const item = new vscode.CompletionItem(imported.alias, vscode.CompletionItemKind.Module);
                item.detail = imported.path; items.push(item);
            }
            const variables = new RegExp(`\\b(?:var|fix|each\\s*\\()\\s*(${language.identifier})|(${language.identifier})\\s*:\\s*(?:int|float|number|bool|string|any|list|map)`, 'gu');
            const names = new Set();
            for (const match of clean.matchAll(variables)) names.add(match[1] || match[2]);
            for (const name of names) items.push(new vscode.CompletionItem(name, vscode.CompletionItemKind.Variable));
            return items;
        }
    }, '.'));
    scheduleAll();
}

module.exports = { activate };
