import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import ts from 'typescript';

const source = readFileSync(process.argv[2], 'utf8');
const exports = {};
new Function('exports', ts.transpile(source, { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 }))(exports);
const { appendQueryParam } = exports;

function query(name, value) {
    const params = new URLSearchParams();
    appendQueryParam(params, name, value);
    return params.toString();
}

assert.equal(query('searchTerm', 'a b'), 'searchTerm=a+b');
assert.equal(query('page', 0), 'page=0', 'a falsy number is sent');
assert.equal(query('includeTeams', false), 'includeTeams=false', 'false is sent');
assert.equal(query('searchTerm', ''), 'searchTerm=', 'an empty string is sent');
assert.equal(query('page', null), '', 'null is left out');
assert.equal(query('page', undefined), '', 'undefined is left out');
assert.equal(query('authorities', ['USER', 'TA']), 'authorities=USER&authorities=TA');
assert.equal(query('teamIds', new Set([4, 5])), 'teamIds=4&teamIds=5');
assert.equal(query('since', new Date(Date.UTC(2026, 8, 29, 12))), 'since=2026-09-29T12%3A00%3A00.000Z', 'a date is sent as ISO 8601');
assert.equal(
    query('filter', { since: new Date(Date.UTC(2026, 8, 29, 12)) }),
    'since=2026-09-29T12%3A00%3A00.000Z',
    'a date inside an object is sent as ISO 8601',
);
assert.equal(
    query('search', { searchTerm: 'a b', page: 0, authorities: ['USER', 'TA'], exerciseIds: new Set([4, 5]), scoreRange: { lower: 0.5 }, sortedBy: null }),
    'searchTerm=a+b&page=0&authorities=USER&authorities=TA&exerciseIds=4&exerciseIds=5&scoreRange.lower=0.5',
    'an object sends one key per property without its own name, nested objects as dotted keys',
);
