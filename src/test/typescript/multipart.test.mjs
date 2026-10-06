import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import ts from 'typescript';

const source = readFileSync(process.argv[2], 'utf8');
const http = { post: (url, body) => body };
const angular = {
    '@angular/core': { inject: () => http, Injectable: () => (target) => target },
    '@angular/common/http': { HttpClient: class {} },
};
const exports = {};
const compiled = ts.transpile(source, { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022, experimentalDecorators: true });
new Function('exports', 'require', compiled)(exports, (name) => angular[name]);

const form = new exports.UploadApi().createUpload(
    { title: 'Algorithms' },
    new Blob(['slides']),
    [new Blob(['a']), new Blob(['b'])],
    'n',
    3,
    0.5,
    false,
    'DRAFT',
    [{ number: 1 }],
    { key: 'value' },
    new Set(['x', 'y']),
    new Set([{ number: 2 }]),
    ['DRAFT', 'FINAL'],
    ['FINAL'],
    'quoted',
    ['a', 'b'],
);

const parts = {};
for (const [name, value] of form.entries()) {
    const part = value instanceof Blob ? { type: value.type, body: await value.text() } : value;
    (parts[name] ??= []).push(part);
}
const json = (body) => [{ type: 'application/json', body }];

assert.deepEqual(parts.course, json('{"title":"Algorithms"}'));
assert.deepEqual(parts.file, [{ type: '', body: 'slides' }]);
assert.deepEqual(parts.files, [{ type: '', body: 'a' }, { type: '', body: 'b' }], 'each binary of an array is its own part');
assert.deepEqual(parts.name, ['n']);
assert.deepEqual(parts.count, ['3']);
assert.deepEqual(parts.ratio, ['0.5']);
assert.deepEqual(parts.active, ['false']);
assert.deepEqual(parts.mode, ['DRAFT']);
assert.deepEqual(parts.pages, json('[{"number":1}]'));
assert.deepEqual(parts.labels, json('{"key":"value"}'));
assert.deepEqual(parts.tags, ['x', 'y'], 'an array of strings is sent as repeated fields, the default encoding of OpenAPI');
assert.deepEqual(parts.sections, json('[{"number":2}]'), 'a set of objects is sent as a JSON array');
assert.deepEqual(parts.modes, ['DRAFT', 'FINAL'], 'an array of enum values is sent as repeated fields');
assert.deepEqual(parts.modeRefs, ['FINAL'], 'an array of enum references is sent as repeated fields');
assert.deepEqual(parts["it's"], ['quoted'], 'a field name with an apostrophe keeps its name');
assert.deepEqual(parts.jsonLabels, json('["a","b"]'), 'an encoding with contentType application/json sends one JSON part');
