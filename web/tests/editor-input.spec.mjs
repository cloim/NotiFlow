import { test, expect } from "@playwright/test";
import { checkKoreanWords } from "./helpers.mjs";

for (const width of [1440, 390, 325]) {
  test(`text and link conditions and centered dropdowns save a URL template at ${width}px`, async ({
    page,
  }) => {
    await page.setViewportSize({ width, height: 844 });
    await page.goto("/?preview=1#/rules/new");
    await page.getByLabel("규칙 이름", { exact: true }).fill("공유 링크 수집");
    await page.getByRole("button", { name: "조건 추가", exact: true }).click();
    const mime = page.getByLabel("조건 1 콘텐츠 타입 선택", { exact: true });
    await expect(
      mime.getByRole("option", { name: "텍스트", exact: true }),
    ).toHaveAttribute("value", "text/plain");
    await expect(
      mime.getByRole("option", { name: "링크", exact: true }),
    ).toHaveAttribute("value", "text/uri-list");
    await expect(
      mime.getByRole("option", { name: "텍스트와 링크", exact: true }),
    ).toHaveCount(0);
    await mime.selectOption({ label: "텍스트" });
    await expect(page.getByLabel("조건 1 값", { exact: true })).toHaveValue(
      "text/plain",
    );
    await mime.selectOption({ label: "링크" });
    await expect(page.getByLabel("조건 1 값", { exact: true })).toHaveValue(
      "text/uri-list",
    );
    const positions = await page
      .locator("select:visible")
      .evaluateAll((selects) =>
        selects.map((select) => {
          const box = select.getBoundingClientRect();
          const icon = select.parentElement
            .querySelector("svg")
            .getBoundingClientRect();
          return {
            label: select.getAttribute("aria-label"),
            centerDifference: Math.abs(
              box.y + box.height / 2 - icon.y - icon.height / 2,
            ),
            rightInset: box.right - icon.right,
          };
        }),
      );
    for (const position of positions) {
      expect(position.centerDifference, position.label).toBeLessThanOrEqual(1);
      expect(position.rightInset, position.label).toBeGreaterThanOrEqual(10);
      expect(position.rightInset, position.label).toBeLessThanOrEqual(14);
    }
    await page
      .getByLabel("웹훅 URL", { exact: true })
      .fill("https://example.com/api/v1/collections/example/documents/url");
    const template = page.getByLabel("요청 본문 템플릿", { exact: true });
    await page.getByText("전송 상세 설정", { exact: true }).click();
    await template.fill('{ "url": {{url}} }');
    await expect(
      page.getByText("공유 링크 주소는", { exact: false }),
    ).toBeVisible();
    await checkKoreanWords(page);
    await page.screenshot({
      path: `../docs/validation/link-rule-${width}.png`,
      fullPage: true,
    });
    await page.getByRole("button", { name: "규칙 저장", exact: true }).click();
    await expect(
      page.getByRole("link", { name: "공유 링크 수집", exact: true }),
    ).toBeVisible();
    const saved = await page.evaluate(() =>
      JSON.parse(localStorage.getItem("notiflow.preview.rules.v2")),
    );
    expect(saved[0].conditionExpression.rows[0].value).toBe("text/uri-list");
    expect(saved[0].webhook.method).toBe("POST");
    expect(saved[0].webhook.payloadTemplate).toBe('{ "url": {{url}} }');
  });
}
