import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import ts from 'typescript';

function load(file, modules) {
    const exports = {};
    const compiled = ts.transpile(readFileSync(file, 'utf8'), { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022, experimentalDecorators: true });
    new Function('exports', 'require', compiled)(exports, (name) => modules[name]);
    return exports;
}

const file = process.argv[2];
const request = (callback) => callback;
const generated = load(file, {
    '@angular/common/http': { httpResource: Object.assign(request, { text: request, blob: request }), HttpClient: class {} },
    '@angular/core': { inject: () => ({}), Injectable: () => (target) => target },
    './query-params': load(join(dirname(file), 'query-params.ts'), {}),
});

assert.deepEqual(
    generated.readReviewsResource('first', 'second')(),
    { url: '/api/reviews', headers: { token: 'first', tokenValue: 'second' } },
    'each header argument keeps its own value',
);
assert.deepEqual(
    generated.readReviewResource('path-value', 'header-value', () => ({ tokenPath: 'query-value' }))(),
    { url: '/api/reviews/path-value?tokenPath=query-value', headers: { tokenValue: 'header-value' } },
    'path, header and query arguments keep their own values',
);
