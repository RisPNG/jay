const link = window.location.href;
for (const [id, packageName] of [
    ["open-full", "com.rispng.jay"],
    ["open-lite", "com.rispng.jay.lite"],
]) {
    const store = `https://play.google.com/store/apps/details?id=${packageName}`;
    document.getElementById(id).href =
        `intent://jay.poppybit.com${window.location.pathname}#Intent;scheme=https;` +
        `package=${packageName};S.jay_link=${encodeURIComponent(link)};` +
        `S.browser_fallback_url=${encodeURIComponent(store)};end`;
}
