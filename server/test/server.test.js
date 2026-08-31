const assert = require("node:assert/strict");
const { after, before, test } = require("node:test");
const { createApp } = require("../src/app");
const { openDatabase } = require("../src/database");

const API_KEY = "test-api-key";
let baseUrl;
let database;
let server;

before(async () => {
  database = openDatabase(":memory:");
  const app = createApp({ database, apiKey: API_KEY });
  server = app.listen(0, "127.0.0.1");
  await new Promise((resolve, reject) => {
    server.once("listening", resolve);
    server.once("error", reject);
  });
  baseUrl = `http://127.0.0.1:${server.address().port}`;
});

after(async () => {
  await new Promise((resolve, reject) => {
    server.close((error) => (error ? reject(error) : resolve()));
  });
  database.close();
});

test("GET /health возвращает состояние сервера", async () => {
  const response = await fetch(`${baseUrl}/health`);

  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), { ok: true });
});

test("GET / возвращает web-админку", async () => {
  const response = await fetch(`${baseUrl}/`);
  const body = await response.text();

  assert.equal(response.status, 200);
  assert.match(response.headers.get("content-type"), /text\/html/);
  assert.match(body, /ЛИФЕРЫЧ BMS/);
});

test("GET /styles.css возвращает стили админки", async () => {
  const response = await fetch(`${baseUrl}/styles.css`);
  const body = await response.text();

  assert.equal(response.status, 200);
  assert.match(response.headers.get("content-type"), /text\/css/);
  assert.match(body, /--yellow:\s*#ffc400/);
});

test("POST телеметрии отклоняет неверный ключ", async () => {
  const response = await fetch(`${baseUrl}/api/v1/telemetry`, {
    method: "POST",
    headers: { "content-type": "application/json", "x-api-key": "wrong" },
    body: JSON.stringify({ bms_uid: "bms-rejected" }),
  });

  assert.equal(response.status, 401);
  assert.deepEqual(await response.json(), { ok: false, error: "unauthorized" });
});

test("POST принимает полный Android payload через совместимый endpoint", async () => {
  const payload = createFullPayload();
  const response = await fetch(`${baseUrl}/api/upload.php`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(payload),
  });
  const body = await response.json();

  assert.equal(response.status, 201);
  assert.deepEqual(body, { ok: true, log_id: 1 });
});

test("GET возвращает батарею и историю без api_key", async () => {
  const headers = { "x-api-key": API_KEY };
  const batteriesResponse = await fetch(`${baseUrl}/api/v1/batteries`, { headers });
  const batteriesBody = await batteriesResponse.json();

  assert.equal(batteriesResponse.status, 200);
  assert.equal(batteriesBody.ok, true);
  assert.equal(batteriesBody.batteries.length, 1);
  assert.equal(batteriesBody.batteries[0].bms_uid, "daly-AA:BB:CC:DD:EE:FF");
  assert.equal(batteriesBody.batteries[0].voltage, 52.4);

  const historyResponse = await fetch(
    `${baseUrl}/api/v1/batteries/${encodeURIComponent("daly-AA:BB:CC:DD:EE:FF")}/telemetry?limit=10`,
    { headers },
  );
  const historyBody = await historyResponse.json();

  assert.equal(historyResponse.status, 200);
  assert.equal(historyBody.telemetry.length, 1);
  assert.equal(historyBody.telemetry[0].id, 1);
  assert.equal(historyBody.telemetry[0].api_key, undefined);
  assert.deepEqual(historyBody.telemetry[0].cells, { 1: 3.275, 2: 3.281 });
});

function createFullPayload() {
  return {
    api_key: API_KEY,
    bms_uid: "daly-AA:BB:CC:DD:EE:FF",
    bluetooth_name: "DalyBMS",
    bluetooth_address: "AA:BB:CC:DD:EE:FF",
    voltage: 52.4,
    current: -8.7,
    soc: 76.5,
    remaining_ah: 153,
    estimated_full_ah: 200,
    nominal_capacity_ah: 200,
    capacity_source: "settings",
    cell_diff_v: 0.006,
    min_cell_v: 3.275,
    max_cell_v: 3.281,
    min_temp: 23,
    max_temp: 27,
    charge_mos: true,
    discharge_mos: false,
    cells: { 1: 3.275, 2: 3.281 },
    temps: { 1: 23, 2: 27 },
    errors: ["example warning"],
    raw: {
      status_frame: "A5 01 90 08",
      historical_raw: ["A5 01 D0 08"],
    },
    events: [
      {
        type: "bms_event",
        severity: "info",
        text: "Подключение",
        local_time: "12:34:56",
      },
    ],
  };
}
