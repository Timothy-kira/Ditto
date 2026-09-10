const { test } = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');
const source = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/extensions/webmcp/content.js'), 'utf8');

function page(forms) {
  let listener;
  const document = {
    nodeType: 9, documentElement: null, readyState: 'complete', title: 'Fixture',
    querySelectorAll: selector => selector === 'form' ? forms : [],
    addEventListener() {},
  };
  const port = { onMessage: { addListener() {} }, onDisconnect: { addListener() {} }, postMessage() {} };
  const context = {
    document, location: { href: 'https://example.org/form' },
    history: { pushState() {}, replaceState() {} },
    window: { addEventListener() {} },
    browser: { runtime: { onMessage: { addListener(fn) { listener = fn; } }, connectNative() { return port; } } },
    setTimeout() { return 1; }, clearTimeout() {}, URL,
    HTMLInputElement: class {}, HTMLTextAreaElement: class {}, InputEvent: class {}, Event: class {},
  };
  vm.runInNewContext(source, context);
  return message => listener(message);
}

test('a page without forms does not invent capabilities', async () => {
  const result = await page([])({ op: 'capabilities' });
  assert.equal(result.ok, true);
  assert.equal(result.capabilities.length, 0);
  assert.ok(result.docId);
});

test('form discovery reports structural evidence rather than business success', async () => {
  const form = { elements: [], id: 'signup', getAttribute() { return null; } };
  const result = await page([form])({ op: 'capabilities' });
  const capability = result.capabilities[0];
  assert.equal(capability.verification_scope, 'structure_only');
  assert.equal(capability.completion.business_success, 'requires_explicit_evidence');
  assert.equal(capability.operation, 'fill_and_validate');
  assert.ok(capability.id.startsWith(result.docId));
  assert.equal(capability.inputs.length, 0);
});

test('execution refuses a capability from another document', async () => {
  const result = await page([])({ op: 'execute_capability', capability_id: 'old:form:1', operation: 'fill_and_validate', fields: [] });
  assert.equal(result.ok, false);
  assert.equal(result.code, 'stale_document');
});

test('execution does not silently upgrade filling to submission', async () => {
  let submits = 0;
  const form = { tagName: 'FORM', isConnected: true, elements: [], id: 'signup', getAttribute() { return null; }, requestSubmit() { submits++; } };
  const send = page([form]);
  const discovery = await send({ op: 'capabilities' });
  const result = await send({ op: 'execute_capability', capability_id: discovery.capabilities[0].id, operation: 'submit', fields: [] });
  assert.equal(result.code, 'unsupported_operation');
  assert.equal(submits, 0);
});

function fixtureField() {
  return {
    nodeType: 1, tagName: 'INPUT', type: 'email', name: 'email', value: '', isConnected: true,
    validity: { valid: true }, willValidate: true,
    getAttribute(name) { return name === 'aria-label' ? 'Email' : null; },
    hasAttribute() { return false; }, focus() {}, dispatchEvent() {},
  };
}

test('all targets are checked before any field changes', async () => {
  const field = fixtureField();
  const form = { tagName: 'FORM', isConnected: true, elements: [field], id: 'signup', getAttribute() { return null; } };
  const send = page([form]);
  const capability = (await send({ op: 'capabilities' })).capabilities[0];
  const result = await send({ op: 'execute_capability', capability_id: capability.id, operation: 'fill_and_validate',
    fields: [{ ref: capability.inputs[0].ref, value: 'a@example.org' }, { ref: '@e99999', value: 'bad' }] });
  assert.equal(result.code, 'invalid_field_target');
  assert.equal(field.value, '');
});

test('fill verifies actual values without claiming a completed transaction', async () => {
  const field = fixtureField();
  const form = { tagName: 'FORM', isConnected: true, elements: [field], id: 'signup', getAttribute() { return null; } };
  const send = page([form]);
  const capability = (await send({ op: 'capabilities' })).capabilities[0];
  const result = await send({ op: 'execute_capability', capability_id: capability.id, operation: 'fill_and_validate',
    fields: [{ ref: capability.inputs[0].ref, value: 'a@example.org' }] });
  assert.equal(result.ok, true);
  assert.equal(field.value, 'a@example.org');
  assert.equal(result.results[0].verified, true);
  assert.equal(result.submitted, false);
  assert.equal(result.business_completed, false);
});
