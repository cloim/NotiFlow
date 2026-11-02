import { test, expect } from "@playwright/test";
import { checkKoreanWords } from "./helpers.mjs";

for (const width of [1440, 390, 325]) {
  test(`native settings starts, copies and stops the server at ${width}px`, async ({
    page,
    context,
  }) => {
    await page.setViewportSize({ width, height: 844 });
    await context.grantPermissions(["clipboard-read", "clipboard-write"]);
    await page.addInitScript(() => {
      const ok = (data) => JSON.stringify({ ok: true, data });
      let state = { status: "stopped", tailscaleConnected: false };
      window.NotiFlowNative = {
        getAuthState: () => ok({ signedIn: true, displayName: "검증 계정" }),
        getShareState: () => ok({}),
        getUpdateState: () => ok({}),
        checkForUpdate: () => ok({}),
        listRules: () => ok({ rules: [] }),
        listLogs: () => ok({ logs: [] }),
        isNotificationListenerEnabled: () => true,
        getServerState: () => ok(state),
        startServer: () => {
          if (!state.tailscaleConnected)
            return JSON.stringify({
              ok: false,
              error: "Tailscale 앱에서 연결을 켠 뒤 다시 실행해 주세요",
            });
          state = { status: "starting", tailscaleConnected: true };
          setTimeout(() => {
            state = {
              status: "running",
              tailscaleConnected: true,
              url: "http://100.100.100.100:8186/",
              accessCode: "test-code-for-ui",
            };
          }, 100);
          return ok(state);
        },
        stopServer: () => {
          state = { status: "stopped", tailscaleConnected: true };
          return ok({});
        },
        openTailscale: () => {
          state.tailscaleConnected = true;
          window.dispatchEvent(new Event("focus"));
          return ok({});
        },
      };
    });
    await page.goto("/#/rules");
    await page.getByRole("button", { name: "계정 메뉴", exact: true }).click();
    await page.getByRole("menuitem", { name: "설정", exact: true }).click();
    await expect(
      page.getByRole("heading", { name: "설정", exact: true }),
    ).toBeVisible();
    await page.getByRole("button", { name: "서버 실행", exact: true }).click();
    await expect(page.getByRole("alert")).toContainText(
      "Tailscale 앱에서 연결",
    );
    await page.getByRole("button", { name: "Tailscale 열기" }).click();
    await expect(
      page.getByText("Tailscale 연결됨", { exact: true }),
    ).toBeVisible();
    await page.getByRole("button", { name: "서버 실행", exact: true }).click();
    await expect(page.getByLabel("접속 주소", { exact: true })).toHaveValue(
      "http://100.100.100.100:8186/",
    );
    await expect(page.getByLabel("접속 코드", { exact: true })).toHaveValue(
      "test-code-for-ui",
    );
    await page
      .getByRole("button", { name: "접속 주소 복사", exact: true })
      .click();
    expect(await page.evaluate(() => navigator.clipboard.readText())).toBe(
      "http://100.100.100.100:8186/",
    );
    await checkKoreanWords(page);
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
    ).toBe(true);
    await page.screenshot({
      path: `../docs/validation/settings-running-${width}.png`,
      fullPage: true,
    });
    await page.getByRole("button", { name: "서버 중지", exact: true }).click();
    await expect(
      page.getByRole("button", { name: "서버 실행", exact: true }),
    ).toBeVisible();
    await expect(page.getByLabel("접속 코드", { exact: true })).toHaveCount(0);
  });
}

async function remoteServer(page) {
  let authorized = true;
  let failWrites = false;
  let rules = [];
  await page.route("**/", async (route) => {
    if (route.request().resourceType() !== "document") return route.continue();
    const response = await route.fetch();
    await route.fulfill({
      response,
      body: (await response.text()).replace(
        "</head>",
        '<meta name="notiflow-remote" content="true"></head>',
      ),
    });
  });
  await page.route("**/api/rpc", async (route) => {
    const { method, args } = route.request().postDataJSON();
    const valid =
      authorized &&
      route.request().headers().authorization === "Bearer correct-test-code";
    if (!valid)
      return route.fulfill({
        status: 401,
        json: { ok: false, error: "접속 코드를 확인해 주세요" },
      });
    let data = {};
    if (method === "getAuthState")
      data = { signedIn: true, displayName: "원격 검증 계정" };
    else if (method === "listRules") data = { rules };
    else if (method === "listLogs")
      data = {
        logs: [
          {
            id: 1,
            ruleId: 1,
            matched: true,
            result: "SUCCESS",
            eventTitle: "공유 문서.pdf",
            message: "전송 완료",
            executedAt: 1000,
          },
        ],
      };
    else if (method === "createRule" || method === "updateRule") {
      await new Promise((resolve) => setTimeout(resolve, 150));
      if (failWrites)
        return route.fulfill({
          status: 400,
          json: { ok: false, error: "서버에서 규칙을 저장하지 못했어요" },
        });
      const input = JSON.parse(args[0]);
      const item = {
        ...input,
        id: input.id || 1,
        targetPackages: [],
        filters: [],
        actions: [{ type: "webhook.post", config: input.webhook }],
      };
      rules = input.id
        ? rules.map((r) => (r.id === input.id ? item : r))
        : [...rules, item];
      data = { ruleId: item.id };
    } else if (method === "setRuleEnabled")
      rules = rules.map((r) =>
        r.id === args[0] ? { ...r, enabled: args[1] } : r,
      );
    else if (method === "deleteRule")
      rules = rules.filter((r) => r.id !== args[0]);
    else throw new Error(`Unexpected remote call: ${method}`);
    await route.fulfill({ json: { ok: true, data } });
  });
  return {
    expire: () => {
      authorized = false;
    },
    failWrites: (value) => {
      failWrites = value;
    },
  };
}

for (const width of [1440, 325]) {
  test(`remote browser authenticates, edits phone rules, views logs and expires at ${width}px`, async ({
    page,
  }) => {
    await page.setViewportSize({ width, height: 844 });
    const server = await remoteServer(page);
    await page.goto("/");
    await expect(
      page.getByRole("heading", { name: "휴대폰에 연결해요" }),
    ).toBeVisible();
    await expect(
      page.getByRole("button", { name: "Google로 계속하기" }),
    ).toHaveCount(0);
    await checkKoreanWords(page);
    await page.getByLabel("접속 코드", { exact: true }).fill("incorrect");
    await page.getByRole("button", { name: "연결하기", exact: true }).click();
    await expect(page.getByRole("alert")).toContainText("접속 코드를 확인");
    await page
      .getByLabel("접속 코드", { exact: true })
      .fill("correct-test-code");
    await page.getByRole("button", { name: "연결하기", exact: true }).click();
    await expect(page.getByText("등록된 규칙이 없어요")).toBeVisible();
    await page.getByRole("link", { name: "새 규칙 만들기" }).click();
    await page
      .getByLabel("규칙 이름", { exact: true })
      .fill("원격으로 만든 규칙");
    await page
      .getByLabel("웹훅 URL", { exact: true })
      .fill("https://example.com/hook");
    server.failWrites(true);
    await page.getByRole("button", { name: "규칙 저장", exact: true }).click();
    await expect(page.getByRole("alert")).toContainText(
      "서버에서 규칙을 저장하지 못했어요",
    );
    await expect(page).toHaveURL(/#\/rules\/new$/);
    server.failWrites(false);
    await page.getByRole("button", { name: "규칙 저장", exact: true }).click();
    await page
      .getByRole("link", { name: "원격으로 만든 규칙", exact: true })
      .click();
    await page.getByRole("link", { name: "규칙 수정", exact: true }).click();
    await page.getByLabel("규칙 이름", { exact: true }).fill("원격 수정 완료");
    await page.getByRole("button", { name: "규칙 저장", exact: true }).click();
    await page.getByRole("switch").click();
    await expect(page.getByRole("switch")).toHaveAttribute(
      "aria-checked",
      "false",
    );
    await page
      .getByRole("link", { name: "원격 수정 완료", exact: true })
      .click();
    await page.getByRole("link", { name: "이 규칙의 실행 기록" }).click();
    await expect(
      page.getByText("공유 문서.pdf", { exact: true }),
    ).toBeVisible();
    await checkKoreanWords(page);
    await page.screenshot({
      path: `../docs/validation/remote-logs-${width}.png`,
      fullPage: true,
    });
    await page.getByRole("button", { name: "계정 메뉴", exact: true }).click();
    await page.getByRole("menuitem", { name: "설정", exact: true }).click();
    await expect(
      page.getByText("휴대폰 서버에 연결되어 있어요", { exact: true }),
    ).toBeVisible();
    await expect(
      page.getByRole("button", { name: "서버 실행", exact: true }),
    ).toHaveCount(0);
    await page
      .getByRole("link", { name: "규칙 목록으로", exact: true })
      .click();
    await page
      .getByRole("link", { name: "원격 수정 완료", exact: true })
      .click();
    await page.getByRole("button", { name: "규칙 삭제", exact: true }).click();
    await page.getByRole("button", { name: "삭제하기", exact: true }).click();
    await expect(page.getByText("등록된 규칙이 없어요")).toBeVisible();
    await page.getByRole("link", { name: "실행 기록", exact: true }).click();
    server.expire();
    await page
      .getByRole("button", { name: "기록 새로고침", exact: true })
      .click();
    await expect(
      page.getByRole("heading", { name: "휴대폰에 연결해요" }),
    ).toBeVisible();
    expect(
      await page.evaluate(() => sessionStorage.getItem("notiflow.remote.code")),
    ).toBe(null);
  });
}
