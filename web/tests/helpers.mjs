import { expect } from "@playwright/test";

export async function checkKoreanWords(page) {
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
