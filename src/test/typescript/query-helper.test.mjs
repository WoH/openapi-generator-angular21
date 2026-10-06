import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import ts from 'typescript';

const source = readFileSync(process.argv[2], 'utf8');
const exports = {};
new Function('exports', ts.transpile(source, { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 }))(exports);
const { appendQueryParam } = exports;

function query(name, value, ...styleAndExplode) {
    const params = new URLSearchParams();
    appendQueryParam(params, name, value, ...styleAndExplode);
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
assert.equal(query('days', [new Date(Date.UTC(2026, 8, 29))]), 'days=2026-09-29T00%3A00%3A00.000Z', 'a date in an array is sent as ISO 8601');
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
assert.throws(() => query('sort', [{ property: 'title' }]), /sort/, 'an object in an array has no OpenAPI query format, so it fails instead of sending [object Object]');
assert.throws(() => query('search', { orders: [{ property: 'title' }] }), /orders/, 'an object in a nested array fails the same way');

assert.equal(query('ids', [1, 2, 3], 'form', false), 'ids=1%2C2%2C3', 'form without explode joins an array with commas');
assert.equal(query('point', { x: 1, y: 2 }, 'form', false), 'point=x%2C1%2Cy%2C2', 'form without explode joins keys and values with commas');
assert.equal(query('pipes', ['a', 'b'], 'pipeDelimited', false), 'pipes=a%7Cb', 'pipeDelimited joins an array with pipes');
assert.equal(query('point', { x: 1, y: 2 }, 'pipeDelimited', false), 'point=x%7C1%7Cy%7C2', 'pipeDelimited joins keys and values with pipes');
assert.equal(query('point', { x: 1, y: 2 }, 'spaceDelimited', false), 'point=x+1+y+2', 'spaceDelimited joins keys and values with spaces');
assert.equal(query('spaces', ['a', 'b'], 'spaceDelimited', false), 'spaces=a+b', 'spaceDelimited joins an array with spaces');
assert.equal(query('ids', [1, 2], 'pipeDelimited', true), 'ids=1&ids=2', 'a delimited style with explode repeats the key');
assert.equal(
    query('filter', { name: 'x', range: { min: 1 } }, 'deepObject', true),
    'filter%5Bname%5D=x&filter%5Brange%5D%5Bmin%5D=1',
    'deepObject sends name[key] keys, nested objects as name[key][key]',
);
