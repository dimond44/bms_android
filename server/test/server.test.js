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
  assert.match(body, /Основное/);
  assert.match(body, /Запись параметров/);
  assert.match(body, /Записать/);
  assert.match(body, /Архив записей/);
  assert.match(body, /Сервис/);
});

test("GET /api/v1/config-template отдаёт шаблон и требует ключ", async () => {
  const unauthorized = await fetch(`${baseUrl}/api/v1/config-template`);
  assert.equal(unauthorized.status, 401);

  const response = await fetch(`${baseUrl}/api/v1/config-template`, {
    headers: { "x-api-key": API_KEY },
  });
  assert.equal(response.status, 200);
  const body = await response.json();
  assert.equal(body.ok, true);
  assert.equal(body.template.id, "liferych-lfp-default");
  assert.ok(Array.isArray(body.template.parameters));
  assert.ok(body.template.parameters.some((item) => item.key === "sleep_timeout"));
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
  const batteriesResponse = await fetch(`${baseUrl}/api/v1/batteries`);
  const batteriesBody = await batteriesResponse.json();

  assert.equal(batteriesResponse.status, 200);
  assert.equal(batteriesBody.ok, true);
  assert.equal(batteriesBody.batteries.length, 1);
  assert.equal(batteriesBody.batteries[0].bms_uid, "daly-AA:BB:CC:DD:EE:FF");
  assert.equal(batteriesBody.batteries[0].voltage, 52.4);
  assert.equal(batteriesBody.batteries[0].remaining_ah, 153);
  assert.equal(batteriesBody.batteries[0].charge_mos, true);
  assert.deepEqual(batteriesBody.batteries[0].cells, { 1: 3.275, 2: 3.281 });

  const historyResponse = await fetch(
    `${baseUrl}/api/v1/batteries/${encodeURIComponent("daly-AA:BB:CC:DD:EE:FF")}/telemetry?limit=10`,
  );
  const historyBody = await historyResponse.json();

  assert.equal(historyResponse.status, 200);
  assert.equal(historyBody.telemetry.length, 1);
  assert.equal(historyBody.telemetry[0].id, 1);
  assert.equal(historyBody.telemetry[0].api_key, undefined);
  assert.deepEqual(historyBody.telemetry[0].cells, { 1: 3.275, 2: 3.281 });
});

test("POST проверки конфигурации сохраняет историю и возвращает последнюю", async () => {
  const firstPayload = createConfigCheckPayload();
  const firstResponse = await fetch(`${baseUrl}/api/config_upload.php`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(firstPayload),
  });

  assert.equal(firstResponse.status, 201);
  assert.deepEqual(await firstResponse.json(), { ok: true, check_id: 1 });

  const secondPayload = createConfigCheckPayload();
  secondPayload.template_check = {
    ...secondPayload.template_check,
    template_version: 4,
    status: "ok",
    checked_at: firstPayload.template_check.checked_at + 1000,
    mismatch_count: 0,
    mismatches: [],
  };
  const secondResponse = await fetch(`${baseUrl}/api/v1/config-check`, {
    method: "POST",
    headers: { "content-type": "application/json", "x-api-key": API_KEY },
    body: JSON.stringify(secondPayload),
  });

  assert.equal(secondResponse.status, 201);
  assert.deepEqual(await secondResponse.json(), { ok: true, check_id: 2 });

  const headers = { "x-api-key": API_KEY };
  const batteriesResponse = await fetch(`${baseUrl}/api/v1/batteries`, { headers });
  const batteriesBody = await batteriesResponse.json();
  const battery = batteriesBody.batteries.find((item) => item.bms_uid === firstPayload.bms_uid);

  assert.equal(battery.bluetooth_name, "ConfigOnlyBMS");
  assert.equal(battery.telemetry_id, null);
  assert.equal(battery.config_check.id, 2);
  assert.equal(battery.config_check.status, "ok");
  assert.equal(battery.config_check.template_version, 4);

  const historyResponse = await fetch(
    `${baseUrl}/api/v1/batteries/${encodeURIComponent(firstPayload.bms_uid)}/config-checks?limit=10`,
    { headers },
  );
  const historyBody = await historyResponse.json();

  assert.equal(historyResponse.status, 200);
  assert.equal(historyBody.config_checks.length, 2);
  assert.deepEqual(historyBody.config_checks.map((check) => check.id), [2, 1]);
  assert.equal(historyBody.config_checks[1].mismatches[0].actual, null);
});

test("Имя Bluetooth DL не помечает батарею как старую красную серию", async () => {
  const payload = createFullPayload();
  payload.bms_uid = "garage-battery";
  payload.bluetooth_name = "Гараж";
  payload.advertised_name = "DL-40D63C3223A2";
  payload.hardware_family = "standard";
  payload.bms_sn = "224LG2504150001";
  payload.bluetooth_id = "D2:1A:07:12:2C:C4";

  const response = await fetch(`${baseUrl}/api/v1/telemetry`, {
    method: "POST",
    headers: { "content-type": "application/json", "x-api-key": API_KEY },
    body: JSON.stringify(payload),
  });

  assert.equal(response.status, 201);

  const batteriesResponse = await fetch(`${baseUrl}/api/v1/batteries`, {
    headers: { "x-api-key": API_KEY },
  });
  const batteriesBody = await batteriesResponse.json();
  const battery = batteriesBody.batteries.find((item) => item.bms_uid === payload.bms_uid);

  assert.equal(battery.bluetooth_name, "Гараж");
  assert.equal(battery.advertised_name, "DL-40D63C3223A2");
  assert.equal(battery.hardware_family, "standard");
  assert.equal(battery.bms_sn, "224LG2504150001");
  assert.equal(battery.bluetooth_id, "DL-40D63C3223A2");
});

test("Профиль владельца сохраняется из телеметрии", async () => {
  const payload = createFullPayload();
  payload.bms_uid = "owner-profile-bms";
  payload.owner_name = "Иван Иванов";
  payload.owner_phone = "+79001234567";
  payload.owner_email = "ivan@example.ru";

  const response = await fetch(`${baseUrl}/api/v1/telemetry`, {
    method: "POST",
    headers: { "content-type": "application/json", "x-api-key": API_KEY },
    body: JSON.stringify(payload),
  });
  assert.equal(response.status, 201);

  const batteriesResponse = await fetch(`${baseUrl}/api/v1/batteries`, {
    headers: { "x-api-key": API_KEY },
  });
  const batteriesBody = await batteriesResponse.json();
  const battery = batteriesBody.batteries.find((item) => item.bms_uid === payload.bms_uid);

  assert.equal(battery.owner_name, "Иван Иванов");
  assert.equal(battery.owner_phone, "+79001234567");
  assert.equal(battery.owner_email, "ivan@example.ru");
});

test("Имя Bluetooth DL не определяет hardware_family", async () => {
  const payload = createFullPayload();
  payload.bms_uid = "inferred-dl-bms";
  payload.bluetooth_name = "DL-ABCDEF123456";
  delete payload.advertised_name;
  delete payload.hardware_family;

  const response = await fetch(`${baseUrl}/api/upload.php`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(payload),
  });

  assert.equal(response.status, 201);

  const batteriesResponse = await fetch(`${baseUrl}/api/v1/batteries`, {
    headers: { "x-api-key": API_KEY },
  });
  const batteriesBody = await batteriesResponse.json();
  const battery = batteriesBody.batteries.find((item) => item.bms_uid === payload.bms_uid);

  assert.equal(battery.hardware_family, "standard");
});

test("POST проверки конфигурации отклоняет неверный ключ", async () => {
  const payload = createConfigCheckPayload();
  payload.api_key = "wrong";
  const response = await fetch(`${baseUrl}/api/config_upload.php`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(payload),
  });

  assert.equal(response.status, 401);
  assert.deepEqual(await response.json(), { ok: false, error: "unauthorized" });
});

test("POST проверки конфигурации валидирует вложенный результат", async () => {
  const payload = createConfigCheckPayload();
  payload.template_check.mismatches = {};
  const response = await fetch(`${baseUrl}/api/v1/config-check`, {
    method: "POST",
    headers: { "content-type": "application/json", "x-api-key": API_KEY },
    body: JSON.stringify(payload),
  });

  assert.equal(response.status, 400);
  assert.deepEqual(await response.json(), { ok: false, error: "invalid_mismatches" });
});

test("очередь записи sleep_timeout ставится и подтверждается приложением", async () => {
  const headers = { "content-type": "application/json", "x-api-key": API_KEY };
  const uid = "write-queue-bms";
  const telemetry = await fetch(`${baseUrl}/api/v1/telemetry`, {
    method: "POST",
    headers,
    body: JSON.stringify({ ...createFullPayload(), bms_uid: uid }),
  });
  assert.equal(telemetry.status, 201);

  const created = await fetch(`${baseUrl}/api/v1/batteries/${encodeURIComponent(uid)}/write-commands`, {
    method: "POST",
    headers,
    body: JSON.stringify({ key: "sleep_timeout", value: 3600 }),
  });
  assert.equal(created.status, 201);
  const createdBody = await created.json();
  assert.equal(createdBody.ok, true);
  assert.equal(createdBody.command.key, "sleep_timeout");
  assert.equal(createdBody.command.status, "pending");
  assert.equal(createdBody.command.register, "0x0115");
  assert.equal(createdBody.command.raw_value, 360);

  const pending = await fetch(
    `${baseUrl}/api/v1/batteries/${encodeURIComponent(uid)}/write-commands?status=pending`,
    { headers: { "x-api-key": API_KEY } },
  );
  const pendingBody = await pending.json();
  assert.equal(pending.status, 200);
  assert.equal(pendingBody.commands.length, 1);
  assert.equal(pendingBody.commands[0].id, createdBody.command.id);

  const writing = await fetch(
    `${baseUrl}/api/v1/batteries/${encodeURIComponent(uid)}/write-commands/${createdBody.command.id}/ack`,
    {
      method: "POST",
      headers,
      body: JSON.stringify({ status: "writing" }),
    },
  );
  assert.equal(writing.status, 200);
  assert.equal((await writing.json()).command.status, "writing");

  const done = await fetch(
    `${baseUrl}/api/v1/batteries/${encodeURIComponent(uid)}/write-commands/${createdBody.command.id}/ack`,
    {
      method: "POST",
      headers,
      body: JSON.stringify({ status: "done", actual: 3600 }),
    },
  );
  assert.equal(done.status, 200);
  const doneBody = await done.json();
  assert.equal(doneBody.command.status, "done");
  assert.equal(doneBody.command.actual, 3600);

  const emptyPending = await fetch(
    `${baseUrl}/api/v1/batteries/${encodeURIComponent(uid)}/write-commands?status=pending`,
    { headers: { "x-api-key": API_KEY } },
  );
  assert.equal((await emptyPending.json()).commands.length, 0);
});

test("очередь записи отклоняет неизвестный параметр", async () => {
  const uid = "write-queue-bms";
  const response = await fetch(
    `${baseUrl}/api/v1/batteries/${encodeURIComponent(uid)}/write-commands`,
    {
      method: "POST",
      headers: { "content-type": "application/json", "x-api-key": API_KEY },
      body: JSON.stringify({ key: "not_a_real_param", value: 1 }),
    },
  );
  assert.equal(response.status, 400);
  assert.deepEqual(await response.json(), { ok: false, error: "unknown_param" });
});

test("очередь записи принимает остальные параметры шаблона", async () => {
  const headers = { "content-type": "application/json", "x-api-key": API_KEY };
  const uid = "write-queue-bms";

  const cellOv = await fetch(`${baseUrl}/api/v1/batteries/${encodeURIComponent(uid)}/write-commands`, {
    method: "POST",
    headers,
    body: JSON.stringify({ key: "cell_over_voltage", value: 3.65 }),
  });
  assert.equal(cellOv.status, 201);
  const cellBody = await cellOv.json();
  assert.equal(cellBody.command.register, "0x0131");
  assert.equal(cellBody.command.raw_value, 3650);

  const temp = await fetch(`${baseUrl}/api/v1/batteries/${encodeURIComponent(uid)}/write-commands`, {
    method: "POST",
    headers,
    body: JSON.stringify({ key: "charge_high_temp", value: 65 }),
  });
  assert.equal(temp.status, 201);
  assert.equal((await temp.json()).command.raw_value, 105);

  const pack = await fetch(`${baseUrl}/api/v1/batteries/${encodeURIComponent(uid)}/write-commands`, {
    method: "POST",
    headers,
    body: JSON.stringify({ key: "pack_over_voltage", value: 14.6 }),
  });
  assert.equal(pack.status, 201);
  assert.equal((await pack.json()).command.raw_value, 146);
});

test("очередь записи принимает балансировку для BMS с именем DL", async () => {
  const headers = { "content-type": "application/json", "x-api-key": API_KEY };
  const uid = "DL-write-skip-bms";
  const telemetry = await fetch(`${baseUrl}/api/v1/telemetry`, {
    method: "POST",
    headers,
    body: JSON.stringify({
      ...createFullPayload(),
      bms_uid: uid,
      bluetooth_name: "DL-4119040183E6",
      advertised_name: "DL-4119040183E6",
    }),
  });
  assert.equal(telemetry.status, 201);

  const response = await fetch(`${baseUrl}/api/v1/batteries/${encodeURIComponent(uid)}/write-commands`, {
    method: "POST",
    headers,
    body: JSON.stringify({ key: "balance_delta", value: 10 }),
  });
  assert.equal(response.status, 201);
  const body = await response.json();
  assert.equal(body.ok, true);
  assert.equal(body.command.key, "balance_delta");
});

test("очередь записи пропускает SOC и баланс для BMS R10K в серийном номере", async () => {
  const headers = { "content-type": "application/json", "x-api-key": API_KEY };
  const uid = "r10k-skip-bms";
  const telemetry = await fetch(`${baseUrl}/api/v1/telemetry`, {
    method: "POST",
    headers,
    body: JSON.stringify({
      ...createFullPayload(),
      bms_uid: uid,
      bms_sn: "221KA160900368R10K",
    }),
  });
  assert.equal(telemetry.status, 201);

  const batteriesResponse = await fetch(`${baseUrl}/api/v1/batteries`);
  const battery = (await batteriesResponse.json()).batteries.find((item) => item.bms_uid === uid);
  assert.equal(battery.hardware_family, "r10k");
  assert.equal(battery.bms_version, "R10K");

  const skipped = await fetch(`${baseUrl}/api/v1/batteries/${encodeURIComponent(uid)}/write-commands`, {
    method: "POST",
    headers,
    body: JSON.stringify({ key: "soc_calibration_100", value: 3.65 }),
  });
  assert.equal(skipped.status, 400);
  assert.equal((await skipped.json()).error, "skipped_for_r10k");

  const allowed = await fetch(`${baseUrl}/api/v1/batteries/${encodeURIComponent(uid)}/write-commands`, {
    method: "POST",
    headers,
    body: JSON.stringify({ key: "sleep_timeout", value: 3600 }),
  });
  assert.equal(allowed.status, 201);
});

test("очередь записи пропускает SOC и баланс по версии BMS R10K", async () => {
  const headers = { "content-type": "application/json", "x-api-key": API_KEY };
  const uid = "r10k-version-skip-bms";
  const telemetry = await fetch(`${baseUrl}/api/v1/telemetry`, {
    method: "POST",
    headers,
    body: JSON.stringify({
      ...createFullPayload(),
      bms_uid: uid,
      bms_sn: "221KA160900368",
      bms_version: "R10K",
      hardware_family: "r10k",
    }),
  });
  assert.equal(telemetry.status, 201);

  const batteriesResponse = await fetch(`${baseUrl}/api/v1/batteries`);
  const battery = (await batteriesResponse.json()).batteries.find((item) => item.bms_uid === uid);
  assert.equal(battery.hardware_family, "r10k");
  assert.equal(battery.bms_version, "R10K");

  const skipped = await fetch(`${baseUrl}/api/v1/batteries/${encodeURIComponent(uid)}/write-commands`, {
    method: "POST",
    headers,
    body: JSON.stringify({ key: "balance_start_voltage", value: 3.4 }),
  });
  assert.equal(skipped.status, 400);
  assert.equal((await skipped.json()).error, "skipped_for_r10k");
});

test("версии R24TK и R24TH проверяют SOC и баланс", async () => {
  const headers = { "content-type": "application/json", "x-api-key": API_KEY };

  for (const version of ["R24TK", "R24TH"]) {
    const uid = `balanced-${version.toLowerCase()}-bms`;
    const telemetry = await fetch(`${baseUrl}/api/v1/telemetry`, {
      method: "POST",
      headers,
      body: JSON.stringify({
        ...createFullPayload(),
        bms_uid: uid,
        bms_sn: "221KA160900368",
        bms_version: version,
        hardware_family: "standard",
      }),
    });
    assert.equal(telemetry.status, 201);

    const batteriesResponse = await fetch(`${baseUrl}/api/v1/batteries`);
    const battery = (await batteriesResponse.json()).batteries.find((item) => item.bms_uid === uid);
    assert.equal(battery.hardware_family, "standard");
    assert.equal(battery.bms_version, version);

    const allowed = await fetch(`${baseUrl}/api/v1/batteries/${encodeURIComponent(uid)}/write-commands`, {
      method: "POST",
      headers,
      body: JSON.stringify({ key: "soc_calibration_100", value: 3.65 }),
    });
    assert.equal(allowed.status, 201, version);
  }
});

test("код модели BMS R24TK1A-8S100A даёт версию R24TK", async () => {
  const headers = { "content-type": "application/json", "x-api-key": API_KEY };
  const uid = "battery-code-r24tk";
  const telemetry = await fetch(`${baseUrl}/api/v1/telemetry`, {
    method: "POST",
    headers,
    body: JSON.stringify({
      ...createFullPayload(),
      bms_uid: uid,
      bms_sn: "221KA160900368",
      bms_battery_code: "R24TK1A-8S100A",
    }),
  });
  assert.equal(telemetry.status, 201);

  const batteriesResponse = await fetch(`${baseUrl}/api/v1/batteries`);
  const battery = (await batteriesResponse.json()).batteries.find((item) => item.bms_uid === uid);
  assert.equal(battery.bms_version, "R24TK");
  assert.equal(battery.hardware_family, "standard");

  const allowed = await fetch(`${baseUrl}/api/v1/batteries/${encodeURIComponent(uid)}/write-commands`, {
    method: "POST",
    headers,
    body: JSON.stringify({ key: "balance_delta", value: 10 }),
  });
  assert.equal(allowed.status, 201);
});

test("версия железа A5 0x63 JHB-R10K важнее кода модели R24TK", async () => {
  const headers = { "content-type": "application/json", "x-api-key": API_KEY };
  const uid = "hw-version-r10k";
  const telemetry = await fetch(`${baseUrl}/api/v1/telemetry`, {
    method: "POST",
    headers,
    body: JSON.stringify({
      ...createFullPayload(),
      bms_uid: uid,
      bms_sn: "221KA160900368",
      bms_battery_code: "R24TK1A-8S100A",
      bms_hw_version: "JHB-R10K-V1.0",
    }),
  });
  assert.equal(telemetry.status, 201);

  const batteriesResponse = await fetch(`${baseUrl}/api/v1/batteries`);
  const battery = (await batteriesResponse.json()).batteries.find((item) => item.bms_uid === uid);
  assert.equal(battery.bms_version, "R10K");
  assert.equal(battery.hardware_family, "r10k");

  const skipped = await fetch(`${baseUrl}/api/v1/batteries/${encodeURIComponent(uid)}/write-commands`, {
    method: "POST",
    headers,
    body: JSON.stringify({ key: "soc_calibration_0", value: 2.5 }),
  });
  assert.equal(skipped.status, 400);
  assert.equal((await skipped.json()).error, "skipped_for_r10k");
});

test("POST service-report сохраняет сборку и отдаёт её без api_key", async () => {
  const payload = {
    api_key: API_KEY,
    bms_uid: "service-bms-001",
    bluetooth_name: "JHB-SERVICE01",
    bluetooth_address: "DE:AD:BE:EF:00:01",
    bluetooth_id: "DE:AD:BE:EF:00:01",
    bms_sn: "JHB-SERVICE01",
    source: "service",
    assembler_name: "Петров Пётр",
    template_id: "liferych-lfp-12v",
    capacity_ah: 105,
    written_at: 1_788_182_400_000,
    status: "ok",
    items: [
      {
        key: "sleep_timeout",
        label: "Время ожидания сна",
        expected: 3600,
        actual: 3600,
        unit: "s",
        ok: true,
      },
    ],
  };

  const created = await fetch(`${baseUrl}/api/v1/service-report`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(payload),
  });
  assert.equal(created.status, 201);
  assert.deepEqual(await created.json(), { ok: true, report_id: 1 });

  const headers = { "x-api-key": API_KEY };
  const batteriesResponse = await fetch(`${baseUrl}/api/v1/batteries`, { headers });
  const batteriesBody = await batteriesResponse.json();
  const battery = batteriesBody.batteries.find((item) => item.bms_uid === payload.bms_uid);
  assert.equal(battery.last_source, "service");
  assert.equal(battery.assembler_name, "Петров Пётр");
  assert.equal(battery.bms_sn, "JHB-SERVICE01");
  assert.equal(battery.bluetooth_id, "DL-DEADBEEF0001");
  assert.equal(battery.service_report.assembler_name, "Петров Пётр");
  assert.equal(battery.service_report.template_id, "liferych-lfp-12v");
  assert.equal(battery.service_report.capacity_ah, 105);
  assert.equal(battery.service_report.status, "ok");

  const listResponse = await fetch(
    `${baseUrl}/api/v1/batteries/${encodeURIComponent(payload.bms_uid)}/service-reports?limit=10`,
    { headers },
  );
  const listBody = await listResponse.json();
  assert.equal(listResponse.status, 200);
  assert.equal(listBody.service_reports.length, 1);
  assert.equal(listBody.service_reports[0].api_key, undefined);
  assert.equal(listBody.service_reports[0].assembler_name, "Петров Пётр");
  assert.equal(listBody.service_reports[0].items[0].ok, true);
});

test("POST телеметрии обрезает нули в имени BLE", async () => {
  const payload = createFullPayload();
  payload.bms_uid = "DL-D21A07122CC4\u0000\u0000\u0000xx";
  payload.bluetooth_name = payload.bms_uid;
  payload.advertised_name = payload.bms_uid;
  payload.bms_sn = payload.bms_uid;
  payload.source = "service";
  payload.assembler_name = "Иванов";
  payload.bluetooth_id = "D2:1A:07:12:2C:C4";

  const response = await fetch(`${baseUrl}/api/upload.php`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(payload),
  });
  assert.equal(response.status, 201);

  const headers = { "x-api-key": API_KEY };
  const batteriesResponse = await fetch(`${baseUrl}/api/v1/batteries`, { headers });
  const batteriesBody = await batteriesResponse.json();
  const battery = batteriesBody.batteries.find((item) => item.bms_uid === "DL-D21A07122CC4");
  assert.ok(battery);
  assert.equal(battery.bluetooth_name, "DL-D21A07122CC4");
  assert.equal(battery.bms_sn, null);
  assert.equal(battery.last_source, "service");
  assert.equal(battery.assembler_name, "Иванов");
  assert.equal(battery.bluetooth_id, "DL-D21A07122CC4");
  assert.equal(battery.bms_uid.includes("\0"), false);

  const historyResponse = await fetch(
    `${baseUrl}/api/v1/batteries/${encodeURIComponent("DL-D21A07122CC4")}/telemetry?limit=5`,
    { headers },
  );
  assert.equal(historyResponse.status, 200);
  const historyBody = await historyResponse.json();
  assert.ok(historyBody.telemetry.length >= 1);
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

function createConfigCheckPayload() {
  return {
    api_key: API_KEY,
    bms_uid: "config-only-bms",
    bluetooth_name: "ConfigOnlyBMS",
    bluetooth_address: "11:22:33:44:55:66",
    config: { cell_count: 8, balance_start_v: 3.4 },
    raw: { source: "settings" },
    template_check: {
      template_id: "liferych-8s",
      template_version: 3,
      status: "mismatch",
      checked_at: 1_788_182_400_000,
      series_count: 8,
      mismatch_count: 1,
      missing_count: 1,
      mismatches: [
        {
          key: "balance_start_v",
          label: "Старт балансировки",
          expected: 3.45,
          actual: null,
          unit: "В",
          tolerance: 0.01,
        },
      ],
      missing: [
        {
          key: "sleep_timeout",
          label: "Тайм-аут сна",
          expected: 3600,
          actual: null,
          unit: "с",
          reason: "register_unavailable",
        },
      ],
      unverified: [
        {
          key: "capacity_ah",
          label: "Ёмкость",
          expected: 200,
          actual: 200,
          unit: "А·ч",
          reason: "manual_confirmation",
        },
      ],
    },
  };
}
