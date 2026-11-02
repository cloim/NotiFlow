import test from "node:test";
import assert from "node:assert/strict";
import { ruleForm, validateForm, toPayload } from "../src/rules.js";
const valid = () => ({
  ...ruleForm(),
  name: "이미지 저장",
  url: "https://example.com/hook",
});
test("source only rule, empty rows, and custom headers round trip", () => {
  const form = valid();
  assert.deepEqual(validateForm(form), {});
  assert.deepEqual(toPayload(form).conditionExpression.rows, []);
  assert.equal(toPayload(form).source, "share");
  const edited = ruleForm({ ...toPayload(form), id: 3 });
  assert.equal(edited.url, form.url);
});
test("invalid MIME, parentheses, regex, URL and header values block saving", () => {
  const form = {
    ...valid(),
    url: "javascript:alert(1)",
    headers: '{"a":1}',
    rows: [
      { type: "mime.matches", value: "image", closeParen: 1 },
      { type: "text.regex", value: "[" },
    ],
  };
  const errors = validateForm(form);
  for (const key of ["url", "headers", "row0", "row1", "expression"])
    assert.ok(errors[key], key);
});
test("JSON payload variables preserve native values and reject unsupported placeholders", () => {
  assert.deepEqual(
    validateForm({
      ...valid(),
      payloadTemplate: '{"text":{{text}},"files":{{attachments}}}',
    }),
    {},
  );
  assert.ok(
    validateForm({ ...valid(), payloadTemplate: '{"secret":{{unknown}}}' })
      .payloadTemplate,
  );
});
test("editing preserves the original token until explicitly changed", () => {
  const form = valid();
  assert.equal(Object.hasOwn(toPayload(form).webhook, "token"), false);
  assert.equal(toPayload({ ...form, token: "" }).webhook.token, "");
});

test("shared link payload accepts both source and explicit URL variables", () => {
  for (const variable of ["source", "url"]) {
    const form = {
      ...ruleForm(),
      name: "공유 링크 수집",
      source: "share",
      url: "https://example.com/api/v1/collections/example/documents/url",
      method: "POST",
      payloadTemplate: `{ "url": {{${variable}}} }`,
    };
    assert.deepEqual(validateForm(form), {});
    assert.equal(toPayload(form).webhook.payloadTemplate, form.payloadTemplate);
    assert.equal(toPayload(form).webhook.method, "POST");
  }
});
