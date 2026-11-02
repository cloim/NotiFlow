import { test, expect } from "@playwright/test";
import { mkdirSync } from "node:fs";
import path from "node:path";
import { runLayoutAudit } from "../scripts/audit-layout.mjs";
const out = path.resolve("../docs/validation");
mkdirSync(out, { recursive: true });

async function checkKoreanWords(page) {
  const broken = await page.evaluate(() => {
    const walker = document.createTreeWalker(
      document.body,
      NodeFilter.SHOW_TEXT,
    );
    const result = [];
    while (walker.nextNode()) {
      const node = walker.currentNode;
      if (node.parentElement.closest("script, style, input, textarea, select"))
        continue;
      for (const match of node.textContent.matchAll(/[가-힣]{2,}/g)) {
        const range = document.createRange();
        range.setStart(node, match.index);
        range.setEnd(node, match.index + match[0].length);
        const rects = [...range.getClientRects()].filter(
          (r) => r.width && r.height,
        );
        if (new Set(rects.map((r) => Math.round(r.y))).size > 1)
          result.push(match[0]);
      }
    }
    return result;
  });
  expect(broken).toEqual([]);
}

for (const width of [1440, 390, 325]) {
  test(`create, view, edit, pause and delete a share rule at ${width}px`, async ({
    page,
  }) => {
    await page.setViewportSize({ width, height: width === 1440 ? 900 : 844 });
    const errors = [];
    page.on("pageerror", (error) => errors.push(error.message));
    await page.goto("/?preview=1#/rules");
    await expect(page.getByText("등록된 규칙이 없어요")).toBeVisible();
    await page.getByRole("link", { name: "새 규칙 만들기" }).click();
    await page.getByRole("button", { name: "규칙 저장" }).click();
    await expect(page.getByText("규칙 이름을 입력해 주세요")).toBeVisible();
    await page
      .getByLabel("규칙 이름", { exact: true })
      .fill("공유한 PDF를 문서함으로");
    await page.getByRole("button", { name: "조건 추가", exact: true }).click();
    await page
      .getByLabel("조건 1 콘텐츠 타입 선택")
      .selectOption("application/pdf");
    await page
      .getByLabel("웹훅 URL", { exact: true })
      .fill("https://example.com/hooks/documents");
    await checkKoreanWords(page);
    await page.screenshot({
      path: `${out}/editor-${width}.png`,
      fullPage: true,
    });
    await page.getByRole("button", { name: "규칙 저장" }).click();
    await page
      .getByRole("link", { name: "공유한 PDF를 문서함으로", exact: true })
      .click();
    await expect(
      page.getByText("application/pdf", { exact: false }),
    ).toBeVisible();
    await page.getByRole("link", { name: "규칙 수정", exact: true }).click();
    await page.getByLabel("규칙 이름", { exact: true }).fill("공유 PDF 보관");
    await page.getByRole("button", { name: "규칙 저장" }).click();
    await page.getByRole("switch").click();
    await expect(page.getByRole("switch")).toHaveAttribute(
      "aria-checked",
      "false",
    );
    await page.waitForTimeout(200); // Capture the completed switch transition.
    await page.screenshot({
      path: `${out}/rules-${width}.png`,
      fullPage: true,
    });
    await page
      .getByRole("link", { name: "공유 PDF 보관", exact: true })
      .click();
    await page.getByRole("button", { name: "규칙 삭제", exact: true }).click();
    await expect(page.getByRole("dialog")).toBeVisible();
    await page.screenshot({
      path: `${out}/delete-${width}.png`,
      fullPage: true,
    });
    await page.keyboard.press("Escape");
    await expect(page.getByRole("dialog")).toHaveCount(0);
    await page.getByRole("button", { name: "규칙 삭제", exact: true }).click();
    await page.getByRole("button", { name: "삭제하기", exact: true }).click();
    await expect(page.getByText("등록된 규칙이 없어요")).toBeVisible();
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
    ).toBeTruthy();
    expect(errors).toEqual([]);
  });
}

test("login remains usable with no native bridge and exposes an explicit preview", async ({
  page,
}) => {
  await page.setViewportSize({ width: 325, height: 844 });
  await page.goto("/");
  await expect(
    page.getByRole("button", { name: "Google로 계속하기" }),
  ).toBeDisabled();
  await checkKoreanWords(page);
  const button = await page
    .getByRole("button", { name: "Google로 계속하기" })
    .boundingBox();
  expect(button.y + button.height).toBeLessThan(844);
  await page.screenshot({ path: `${out}/login-325.png`, fullPage: true });
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBeTruthy();
});

test("Google authentication, shared content and update prompt use native events", async ({
  page,
}) => {
  await page.addInitScript(() => {
    const ok = (data) => JSON.stringify({ ok: true, data });
    let signedIn = false;
    window.NotiFlowNative = {
      getAuthState: () => ok({ signedIn }),
      getShareState: () => ok({}),
      getUpdateState: () => ok({}),
      getAppInfo: () => JSON.stringify({ versionName: "1.0.4" }),
      listRules: () => ok({ rules: [] }),
      listLogs: () => ok({ logs: [] }),
      signInWithGoogle: () => {
        setTimeout(() => {
          signedIn = true;
          window.dispatchEvent(
            new CustomEvent("notiflowAuthChanged", {
              detail: {
                signedIn,
                displayName: "검증 계정",
                email: "test@example.com",
              },
            }),
          );
        }, 50);
        return ok({});
      },
      checkForUpdate: () => ok({ started: true }),
      downloadUpdate: () => {
        window.downloadCalled = true;
        return ok({});
      },
      isNotificationListenerEnabled: () => false,
    };
  });
  await page.goto("/");
  await page.getByRole("button", { name: "Google로 계속하기" }).click();
  await expect(page.getByRole("heading", { name: "내 규칙" })).toBeVisible();
  await page.goto("/#/logs");
  await page.evaluate(() =>
    window.dispatchEvent(
      new CustomEvent("notiflowShareChanged", {
        detail: {
          received: true,
          title: "문서.pdf",
          mimeType: "application/pdf",
        },
      }),
    ),
  );
  await expect(page.getByText(/문서.pdf/)).toBeVisible();
  await page.evaluate(() =>
    window.dispatchEvent(
      new CustomEvent("notiflowUpdateChanged", {
        detail: {
          available: true,
          latestVersionName: "2.0.0",
          notes: "공유 기능을 개선했습니다",
        },
      }),
    ),
  );
  await expect(page.getByRole("dialog")).toBeVisible();
  await expect(page.getByText("공유 기능을 개선했습니다")).toBeVisible();
  await page.getByRole("button", { name: "내려받기" }).click();
  expect(await page.evaluate(() => window.downloadCalled)).toBe(true);
});

test("balance layout audit covers all routes and small mobile screens", async () => {
  test.setTimeout(120000);
  runLayoutAudit();
});

for (const width of [1440, 390, 325]) {
  test(`account menu, notification status and removed settings at ${width}px`, async ({
    page,
  }) => {
    await page.setViewportSize({ width, height: 844 });
    await page.addInitScript(() => {
      const ok = (data) => JSON.stringify({ ok: true, data });
      window.testSignedIn = true;
      window.testNotificationAccess = false;
      window.notificationSettingsOpened = 0;
      window.NotiFlowNative = {
        getAuthState: () =>
          ok({
            signedIn: window.testSignedIn,
            displayName: "검증 계정",
            email: "test@example.com",
          }),
        getShareState: () => ok({}),
        getUpdateState: () => ok({}),
        listRules: () => ok({ rules: [] }),
        listLogs: () => ok({ logs: [] }),
        checkForUpdate: () => ok({ started: true }),
        isNotificationListenerEnabled: () => window.testNotificationAccess,
        openNotificationListenerSettings: () => {
          window.notificationSettingsOpened++;
        },
        signOutGoogle: () => {
          window.testSignedIn = false;
          window.dispatchEvent(
            new CustomEvent("notiflowAuthChanged", {
              detail: { signedIn: false },
            }),
          );
          return ok({});
        },
      };
    });
    await page.goto("/#/rules");
    await expect(
      page.getByRole("heading", { name: "내 규칙", exact: true }),
    ).toBeVisible();
    await expect(
      page.getByRole("link", { name: "설정", exact: true }),
    ).toHaveCount(0);
    const permission = page.getByRole("button", { name: /알림 접근 꺼짐/ });
    await expect(permission).toBeVisible();
    await permission.click();
    expect(await page.evaluate(() => window.notificationSettingsOpened)).toBe(
      1,
    );
    await expect(page.getByRole("alert")).toHaveCount(0);
    await page.evaluate(() => {
      window.testNotificationAccess = true;
      window.dispatchEvent(
        new CustomEvent("notiflowNotificationAccessChanged", {
          detail: { enabled: true },
        }),
      );
    });
    await expect(
      page.getByRole("button", { name: /알림 접근 허용됨/ }),
    ).toBeVisible();
    await page.evaluate(() => {
      window.testNotificationAccess = false;
      window.dispatchEvent(new Event("focus"));
    });
    await expect(
      page.getByRole("button", { name: /알림 접근 꺼짐/ }),
    ).toBeVisible();

    const avatar = page.getByRole("button", { name: "계정 메뉴", exact: true });
    await avatar.click();
    await expect(
      page.getByRole("menuitem", { name: "로그아웃", exact: true }),
    ).toBeVisible();
    await expect(
      page.getByRole("menuitem", { name: "설정", exact: true }),
    ).toHaveCount(0);
    await page.screenshot({
      path: `${out}/account-menu-${width}.png`,
      fullPage: true,
    });
    const bounds = await page.getByRole("menu").boundingBox();
    expect(bounds.x).toBeGreaterThanOrEqual(0);
    expect(bounds.x + bounds.width).toBeLessThanOrEqual(width);
    await page.keyboard.press("Escape");
    await expect(page.getByRole("menu")).toHaveCount(0);
    await expect(avatar).toBeFocused();
    await page.keyboard.press("ArrowDown");
    await expect(
      page.getByRole("menuitem", { name: "로그아웃" }),
    ).toBeFocused();
    await page.keyboard.press("Escape");
    await avatar.click();
    await page.getByRole("heading", { name: "내 규칙", exact: true }).click();
    await expect(page.getByRole("menu")).toHaveCount(0);

    await page.goto("/#/settings");
    await expect(page).toHaveURL(/#\/rules$/);
    await page.goto("/#/logs");
    await expect(
      page.getByRole("heading", { name: "실행 기록", exact: true }),
    ).toBeVisible();
    await expect(
      page.getByText("어떤 규칙이 실행됐는지 확인하세요"),
    ).toHaveCount(0);
    await avatar.click();
    await page.getByRole("menuitem", { name: "로그아웃", exact: true }).click();
    await expect(
      page.getByRole("button", { name: "Google로 계속하기" }),
    ).toBeVisible();
  });
}
