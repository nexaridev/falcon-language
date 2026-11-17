'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const vscode = require('vscode');

async function until(check, message) {
    const limit = Date.now() + 20000;
    while (Date.now() < limit) {
        if (await check()) return;
        await new Promise(resolve => setTimeout(resolve, 100));
    }
    throw new Error(message);
}

async function run() {
    const folder = path.resolve(__dirname, '../../../build/editor-fixtures');
    await fs.mkdir(folder, { recursive: true });
    const filename = path.join(folder, 'main.fc');
    await fs.writeFile(path.join(folder, 'math.fc'), 'func square(n: int) -> int { give n * n }');
    await fs.writeFile(filename, 'use math\nfunc main() {\n    say(unknown)\n}\n');
    const document = await vscode.workspace.openTextDocument(filename);
    await vscode.window.showTextDocument(document);
    assert.equal(document.languageId, 'falcon');
    const extension = vscode.extensions.getExtension('falcon-local.falcon-language');
    assert.ok(extension, 'Extension is discoverable');
    await extension.activate();
    await until(() => vscode.languages.getDiagnostics(document.uri).some(d => d.message.includes("Unknown variable 'unknown'")), 'Live diagnostic did not appear');
    const error = vscode.languages.getDiagnostics(document.uri).find(d => d.source === 'Falcon');
    assert.equal(error.range.start.line, 2);
    assert.equal(error.range.start.character, 8);

    const edit = new vscode.WorkspaceEdit();
    edit.replace(document.uri, new vscode.Range(2, 8, 2, 15), '42');
    await vscode.workspace.applyEdit(edit);
    await until(() => vscode.languages.getDiagnostics(document.uri).length === 0, 'Diagnostic did not clear after unsaved fix');
    assert.ok((await fs.readFile(filename, 'utf8')).includes('unknown'), 'Validation must not save user edits');

    const replace = new vscode.WorkspaceEdit();
    replace.replace(document.uri, new vscode.Range(0, 0, document.lineCount, 0), 'use math\nfunc main() {\n    math.\n}\n');
    await vscode.workspace.applyEdit(replace);
    const completions = await vscode.commands.executeCommand('vscode.executeCompletionItemProvider', document.uri, new vscode.Position(2, 9), '.');
    const square = completions.items.find(i => i.label === 'square');
    assert.ok(square, 'Imported function completion');
    assert.match(square.insertText.value, /square\(/);
    const editor = await vscode.window.showTextDocument(document);
    editor.selection = new vscode.Selection(2, 9, 2, 9);
    await vscode.commands.executeCommand('editor.action.triggerSuggest');
    await new Promise(resolve => setTimeout(resolve, 700));
    await vscode.commands.executeCommand('acceptSelectedSuggestion');
    await until(() => document.getText().includes('math.square(n)'), 'Suggestion acceptance did not insert function and parameter placeholder');
    await vscode.commands.executeCommand('jumpToNextSnippetPlaceholder');

    const math = await vscode.workspace.openTextDocument(path.join(folder, 'math.fc'));
    await vscode.window.showTextDocument(math);
    await new Promise(resolve => setTimeout(resolve, 1200));
    assert.ok(!vscode.languages.getDiagnostics(math.uri).some(d => /entry point/.test(d.message)), 'Libraries do not need main');
    assert.equal(vscode.workspace.getConfiguration('editor', math).get('tabCompletion'), 'on');
    assert.equal(vscode.workspace.getConfiguration('editor', math).get('snippetSuggestions'), 'top');
    const nativeFile = path.join(folder, 'native.fc');
    await fs.writeFile(nativeFile, 'use "std/math.fc" as math\nfunc main() {\n    math.\n}\n');
    const nativeDocument = await vscode.workspace.openTextDocument(nativeFile);
    await vscode.window.showTextDocument(nativeDocument);
    const nativeCompletions = await vscode.commands.executeCommand('vscode.executeCompletionItemProvider', nativeDocument.uri, new vscode.Position(2, 9), '.');
    assert.ok(nativeCompletions.items.some(i => i.label === 'factorial'), 'Bundled factorial completion');
    assert.ok(nativeCompletions.items.some(i => i.label === 'sqrt'), 'Bundled sqrt completion');
    assert.match(nativeCompletions.items.find(i => i.label === 'square').detail, /value: number.*-> number/);
    for (const [name, member] of [['io','readLine'], ['text','split'], ['collections','newMap'], ['fs','readText'], ['path','join'], ['random','integer'], ['time','now'], ['json','parse'], ['os','args'], ['test','assert'], ['http','get'], ['gui','button']]) {
        const file = path.join(folder, `completion-${name}.fc`);
        await fs.writeFile(file, `use ${name}\nfunc main() {\n    ${name}.\n}\n`);
        const doc = await vscode.workspace.openTextDocument(file);
        const result = await vscode.commands.executeCommand('vscode.executeCompletionItemProvider', doc.uri, new vscode.Position(2, 5 + name.length), '.');
        assert.ok(result.items.some(i => i.label === member), `Bundled ${name}.${member} completion`);
    }
    for (const [name, member] of [['telebot', 'send_message'], ['pillow', 'resize']]) {
        const filename = path.join(folder, 'optional-' + name + '.fc');
        const library = path.relative(folder, path.resolve(__dirname, '../../../libraries', name + '.fc')).replaceAll('\\', '/');
        await fs.writeFile(filename, 'use "' + library + '" as lib\nfunc main() {\n    lib.\n}\n');
        const doc = await vscode.workspace.openTextDocument(filename);
        const results = await vscode.commands.executeCommand('vscode.executeCompletionItemProvider', doc.uri, new vscode.Position(2, 8), '.');
        assert.ok(results.items.some(item => item.label === member), 'Optional library completion: ' + name);
    }
    const numberFile = path.join(folder, 'number.fc');
    await fs.writeFile(numberFile, 'use "std/math.fc" as math\nfunc main() { var value: number = 2.5\nsay(math.square(value)) }');
    const numberDoc = await vscode.workspace.openTextDocument(numberFile);
    await vscode.window.showTextDocument(numberDoc);
    await vscode.commands.executeCommand('falcon.checkDocument');
    await new Promise(resolve => setTimeout(resolve, 1600));
    assert.equal(vscode.languages.getDiagnostics(numberDoc.uri).length, 0, 'number program checks without errors');
    // Use the actual literal offset, so this assertion follows the fixture text.
    const line = numberDoc.lineAt(1).text;
    const bad = new vscode.WorkspaceEdit();
    bad.replace(numberDoc.uri, new vscode.Range(1, line.indexOf('2.5'), 1, line.indexOf('2.5') + 3), '"bad"');
    await vscode.workspace.applyEdit(bad);
    await until(() => vscode.languages.getDiagnostics(numberDoc.uri).some(d => d.message.includes('Expected number, got string')), 'number type error diagnostic');
    console.log('Passed VS Code integration: activation, diagnostics, unsaved fixes, module completion, libraries, Tab defaults.');
    await fs.writeFile(path.resolve(__dirname, '../../../build/editor-integration-result.txt'), 'PASS\n');
}

module.exports = { run };
