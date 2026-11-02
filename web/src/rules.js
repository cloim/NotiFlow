export const conditionTypes = [
  ["mime.matches", "콘텐츠 타입"],
  ["title.contains", "제목에 포함"],
  ["text.contains", "본문에 포함"],
  ["text.regex", "본문 정규식"],
];
export const mimeTypes = [
  ["*/*", "모든 콘텐츠"],
  ["text/*", "텍스트와 링크"],
  ["image/*", "이미지"],
  ["application/pdf", "PDF"],
  ["video/*", "동영상"],
  ["audio/*", "오디오"],
  ["custom", "직접 입력"],
];
export const webhookOf = (rule) =>
  rule.actions?.find((a) => a.type === "webhook.post")?.config ||
  rule.webhook ||
  {};
export function ruleForm(rule) {
  const webhook = rule ? webhookOf(rule) : {};
  return {
    id: rule?.id,
    name: rule?.name || "",
    source: rule?.source || "share",
    enabled: rule?.enabled ?? true,
    packageName: rule?.targetPackages?.[0] || "",
    priority: rule?.priority ?? 100,
    rows: rule?.conditionExpression?.rows?.map((r) => ({ ...r })) || [],
    url: webhook.url || "",
    method: webhook.method || "POST",
    headers: JSON.stringify(webhook.headers || {}, null, 2),
    payloadTemplate: webhook.payloadTemplate || "",
    token: undefined,
  };
}
export function validateForm(form) {
  const errors = {};
  if (!form.name.trim()) errors.name = "규칙 이름을 입력해 주세요";
  try {
    const url = new URL(form.url);
    if (!["https:", "http:"].includes(url.protocol)) throw new Error();
  } catch {
    errors.url = "http 또는 https로 시작하는 URL을 입력해 주세요";
  }
  try {
    const headers = JSON.parse(form.headers || "{}");
    if (
      !headers ||
      Array.isArray(headers) ||
      typeof headers !== "object" ||
      Object.values(headers).some((v) => typeof v !== "string")
    )
      throw new Error();
    if (
      Object.entries(headers).some(
        ([k, v]) =>
          !/^[!#$%&'*+.^_`|~0-9A-Za-z-]+$/.test(k) || /[\r\n]/.test(v),
      )
    )
      throw new Error();
  } catch {
    errors.headers = "헤더 이름과 문자열 값으로 된 JSON 객체를 입력해 주세요";
  }
  let balance = 0;
  form.rows.forEach((row, i) => {
    if (!row.value.trim()) errors[`row${i}`] = "조건 값을 입력해 주세요";
    if (row.type === "text.regex") {
      try {
        new RegExp(row.value);
      } catch {
        errors[`row${i}`] = "정규식 형식을 확인해 주세요";
      }
    }
    if (
      row.type === "mime.matches" &&
      !/^[\w!#$&^.+*-]+\/[\w!#$&^.+*-]+$/.test(row.value)
    )
      errors[`row${i}`] = "MIME 타입을 입력해 주세요. 예: image/*";
    balance += Number(row.openParen || 0);
    balance -= Number(row.closeParen || 0);
    if (balance < 0)
      errors.expression = "여는 괄호와 닫는 괄호의 순서를 확인해 주세요";
  });
  if (balance !== 0)
    errors.expression = "여는 괄호와 닫는 괄호의 수를 맞춰 주세요";
  if (form.payloadTemplate.trim()) {
    try {
      const rendered = form.payloadTemplate.replace(
        /\{\{([^}]+)}}/g,
        (_, key) => {
          if (
            ![
              "packageName",
              "title",
              "text",
              "postedAt",
              "extras",
              "source",
              "mimeType",
              "attachments",
            ].includes(key)
          )
            throw new Error();
          return ["attachments"].includes(key)
            ? "[]"
            : key === "extras"
              ? "{}"
              : key === "postedAt"
                ? "1"
                : '"예시"';
        },
      );
      const body = JSON.parse(rendered);
      if (body === null || typeof body !== "object") throw new Error();
    } catch {
      errors.payloadTemplate = "JSON 형식과 본문 변수를 확인해 주세요";
    }
  }
  return errors;
}
export function toPayload(form) {
  return {
    id: form.id,
    name: form.name.trim(),
    enabled: form.enabled,
    source: form.source,
    packageName: form.packageName.trim(),
    priority: Number(form.priority),
    conditionExpression: {
      rows: form.rows.map((r, i) => ({
        ...r,
        openParen: Number(r.openParen || 0),
        closeParen: Number(r.closeParen || 0),
        operator: i === form.rows.length - 1 ? null : r.operator || "AND",
      })),
    },
    webhook: {
      url: form.url.trim(),
      method: form.method,
      headers: JSON.parse(form.headers || "{}"),
      payloadTemplate: form.payloadTemplate.trim(),
      ...(form.token !== undefined ? { token: form.token } : {}),
    },
  };
}
