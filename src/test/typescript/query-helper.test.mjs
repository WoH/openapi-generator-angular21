import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import ts from 'typescript';

const source = readFileSync(new URL('../../main/resources/angular22/queryObjectHelper.mustache', import.meta.url), 'utf8');
const appendQueryObject = new Function(`${ts.transpile(source, { target: ts.ScriptTarget.ES2022 })}\nreturn appendQueryObject;`)();

function query(value) {
    const params = new URLSearchParams();
    appendQueryObject(params, value);
    return params.toString();
}

assert.equal(
    query({ searchTerm: 'a b', page: 0, authorities: ['USER', 'TA'], exerciseIds: new Set([4, 5]), scoreRange: { lower: 0.5 }, sortedBy: null }),
    'searchTerm=a+b&page=0&authorities=USER&authorities=TA&exerciseIds=4&exerciseIds=5&scoreRange.lower=0.5',
);
