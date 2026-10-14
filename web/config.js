const API_URL = "https://panalera-production-72fa.up.railway.app";
const _fetch = window.fetch;
window.fetch = (url, opts) =>
  _fetch(typeof url === "string" && url.startsWith("/") ? API_URL + url : url, opts);