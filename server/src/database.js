const fs = require("node:fs");
const path = require("node:path");
const Database = require("better-sqlite3");

function openDatabase(filename) {
  if (filename !== ":memory:") {
    fs.mkdirSync(path.dirname(filename), { recursive: true });
  }

  const db = new Database(filename);
  db.pragma("journal_mode = WAL");
  db.pragma("foreign_keys = ON");

  db.exec(`
    CREATE TABLE IF NOT EXISTS batteries (
      bms_uid TEXT PRIMARY KEY,
      bluetooth_name TEXT,
      bluetooth_address TEXT,
      first_seen_at INTEGER NOT NULL,
      last_seen_at INTEGER NOT NULL
    );

    CREATE TABLE IF NOT EXISTS telemetry (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      bms_uid TEXT NOT NULL,
      received_at INTEGER NOT NULL,
      voltage REAL,
      current REAL,
      soc REAL,
      remaining_ah REAL,
      estimated_full_ah REAL,
      nominal_capacity_ah REAL,
      capacity_source TEXT,
      cell_diff_v REAL,
      min_cell_v REAL,
      max_cell_v REAL,
      min_temp INTEGER,
      max_temp INTEGER,
      charge_mos INTEGER,
      discharge_mos INTEGER,
      cells_json TEXT NOT NULL,
      temps_json TEXT NOT NULL,
      errors_json TEXT NOT NULL,
      raw_json TEXT NOT NULL,
      events_json TEXT NOT NULL,
      payload_json TEXT NOT NULL,
      FOREIGN KEY (bms_uid) REFERENCES batteries(bms_uid) ON DELETE CASCADE
    );

    CREATE INDEX IF NOT EXISTS telemetry_bms_time_idx
      ON telemetry(bms_uid, received_at DESC);
  `);

  const upsertBattery = db.prepare(`
    INSERT INTO batteries (
      bms_uid, bluetooth_name, bluetooth_address, first_seen_at, last_seen_at
    ) VALUES (
      @bms_uid, @bluetooth_name, @bluetooth_address, @received_at, @received_at
    )
    ON CONFLICT(bms_uid) DO UPDATE SET
      bluetooth_name = excluded.bluetooth_name,
      bluetooth_address = excluded.bluetooth_address,
      last_seen_at = excluded.last_seen_at
  `);

  const insertTelemetryRow = db.prepare(`
    INSERT INTO telemetry (
      bms_uid, received_at, voltage, current, soc, remaining_ah,
      estimated_full_ah, nominal_capacity_ah, capacity_source, cell_diff_v,
      min_cell_v, max_cell_v, min_temp, max_temp, charge_mos, discharge_mos,
      cells_json, temps_json, errors_json, raw_json, events_json, payload_json
    ) VALUES (
      @bms_uid, @received_at, @voltage, @current, @soc, @remaining_ah,
      @estimated_full_ah, @nominal_capacity_ah, @capacity_source, @cell_diff_v,
      @min_cell_v, @max_cell_v, @min_temp, @max_temp, @charge_mos, @discharge_mos,
      @cells_json, @temps_json, @errors_json, @raw_json, @events_json, @payload_json
    )
  `);

  const saveTelemetry = db.transaction((payload, receivedAt) => {
    const row = {
      bms_uid: payload.bms_uid,
      bluetooth_name: payload.bluetooth_name ?? null,
      bluetooth_address: payload.bluetooth_address ?? null,
      received_at: receivedAt,
      voltage: payload.voltage ?? null,
      current: payload.current ?? null,
      soc: payload.soc ?? null,
      remaining_ah: payload.remaining_ah ?? null,
      estimated_full_ah: payload.estimated_full_ah ?? null,
      nominal_capacity_ah: payload.nominal_capacity_ah ?? null,
      capacity_source: payload.capacity_source ?? null,
      cell_diff_v: payload.cell_diff_v ?? null,
      min_cell_v: payload.min_cell_v ?? null,
      max_cell_v: payload.max_cell_v ?? null,
      min_temp: payload.min_temp ?? null,
      max_temp: payload.max_temp ?? null,
      charge_mos: booleanToInteger(payload.charge_mos),
      discharge_mos: booleanToInteger(payload.discharge_mos),
      cells_json: JSON.stringify(payload.cells ?? {}),
      temps_json: JSON.stringify(payload.temps ?? {}),
      errors_json: JSON.stringify(payload.errors ?? []),
      raw_json: JSON.stringify(payload.raw ?? {}),
      events_json: JSON.stringify(payload.events ?? []),
      payload_json: JSON.stringify(payload),
    };

    upsertBattery.run(row);
    return Number(insertTelemetryRow.run(row).lastInsertRowid);
  });

  return {
    close: () => db.close(),

    insertTelemetry(payload, receivedAt = Date.now()) {
      return saveTelemetry(payload, receivedAt);
    },

    hasBattery(bmsUid) {
      return db.prepare("SELECT 1 FROM batteries WHERE bms_uid = ?").get(bmsUid) != null;
    },

    listBatteries() {
      const rows = db.prepare(`
        SELECT
          b.bms_uid,
          b.bluetooth_name,
          b.bluetooth_address,
          b.first_seen_at,
          b.last_seen_at,
          t.id AS telemetry_id,
          t.received_at,
          t.voltage,
          t.current,
          t.soc,
          t.errors_json
        FROM batteries b
        LEFT JOIN telemetry t ON t.id = (
          SELECT latest.id
          FROM telemetry latest
          WHERE latest.bms_uid = b.bms_uid
          ORDER BY latest.received_at DESC, latest.id DESC
          LIMIT 1
        )
        ORDER BY b.last_seen_at DESC
      `).all();

      return rows.map((row) => {
        const { errors_json: errorsJson, ...battery } = row;
        return {
          ...battery,
          errors: parseJson(errorsJson, []),
        };
      });
    },

    listTelemetry(bmsUid, limit) {
      return db.prepare(`
        SELECT id, received_at, payload_json
        FROM telemetry
        WHERE bms_uid = ?
        ORDER BY received_at DESC, id DESC
        LIMIT ?
      `).all(bmsUid, limit).map((row) => ({
        ...parseJson(row.payload_json, {}),
        id: row.id,
        received_at: row.received_at,
      }));
    },
  };
}

function booleanToInteger(value) {
  if (value === true) return 1;
  if (value === false) return 0;
  return null;
}

function parseJson(value, fallback) {
  if (value == null) return fallback;
  try {
    return JSON.parse(value);
  } catch {
    return fallback;
  }
}

module.exports = { openDatabase };
