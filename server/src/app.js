const express = require("express");
const path = require("node:path");

const NUMERIC_FIELDS = [
  "voltage",
  "current",
  "soc",
  "remaining_ah",
  "estimated_full_ah",
  "nominal_capacity_ah",
  "cell_diff_v",
  "min_cell_v",
  "max_cell_v",
  "min_temp",
  "max_temp",
];

function createApp({ database, apiKey }) {
  if (!database) throw new TypeError("database is required");
  if (typeof apiKey !== "string" || apiKey.length === 0) {
    throw new TypeError("apiKey must be a non-empty string");
  }

  const app = express();
  const publicDir = path.join(__dirname, "..", "public");
  app.disable("x-powered-by");
  app.use(express.json({ limit: "2mb" }));
  app.use(express.static(publicDir));

  app.get("/health", (_request, response) => {
    response.json({ ok: true });
  });

  const receiveTelemetry = (request, response) => {
    const suppliedKey = request.get("x-api-key") || request.body?.api_key;
    if (suppliedKey !== apiKey) {
      return response.status(401).json({ ok: false, error: "unauthorized" });
    }

    const validationError = validateTelemetry(request.body);
    if (validationError) {
      return response.status(400).json({ ok: false, error: validationError });
    }

    const payload = { ...request.body };
    delete payload.api_key;

    const logId = database.insertTelemetry(payload);
    return response.status(201).json({ ok: true, log_id: logId });
  };

  app.post("/api/v1/telemetry", receiveTelemetry);
  app.post("/api/upload.php", receiveTelemetry);

  const requireHeaderApiKey = (request, response, next) => {
    if (request.get("x-api-key") !== apiKey) {
      return response.status(401).json({ ok: false, error: "unauthorized" });
    }
    return next();
  };

  app.get("/api/v1/batteries", requireHeaderApiKey, (_request, response) => {
    response.json({ ok: true, batteries: database.listBatteries() });
  });

  app.get(
    "/api/v1/batteries/:bmsUid/telemetry",
    requireHeaderApiKey,
    (request, response) => {
      const bmsUid = request.params.bmsUid;
      if (!database.hasBattery(bmsUid)) {
        return response.status(404).json({ ok: false, error: "battery_not_found" });
      }

      const limit = parseLimit(request.query.limit);
      if (limit == null) {
        return response.status(400).json({ ok: false, error: "invalid_limit" });
      }

      return response.json({
        ok: true,
        bms_uid: bmsUid,
        telemetry: database.listTelemetry(bmsUid, limit),
      });
    },
  );

  app.use((_request, response) => {
    response.status(404).json({ ok: false, error: "not_found" });
  });

  app.use((error, _request, response, _next) => {
    if (error?.type === "entity.too.large") {
      return response.status(413).json({ ok: false, error: "payload_too_large" });
    }
    if (error instanceof SyntaxError && error.status === 400 && "body" in error) {
      return response.status(400).json({ ok: false, error: "invalid_json" });
    }

    console.error(error);
    return response.status(500).json({ ok: false, error: "internal_error" });
  });

  return app;
}

function validateTelemetry(payload) {
  if (!isPlainObject(payload)) return "payload_must_be_object";
  if (typeof payload.bms_uid !== "string" || payload.bms_uid.trim().length === 0) {
    return "invalid_bms_uid";
  }

  for (const field of NUMERIC_FIELDS) {
    if (payload[field] != null && !isFiniteNumber(payload[field])) {
      return `invalid_${field}`;
    }
  }

  for (const field of ["charge_mos", "discharge_mos"]) {
    if (payload[field] != null && typeof payload[field] !== "boolean") {
      return `invalid_${field}`;
    }
  }

  for (const field of ["cells", "temps"]) {
    if (payload[field] != null) {
      if (!isPlainObject(payload[field])) return `invalid_${field}`;
      if (Object.values(payload[field]).some((value) => !isFiniteNumber(value))) {
        return `invalid_${field}`;
      }
    }
  }

  if (payload.raw != null && !isPlainObject(payload.raw)) return "invalid_raw";
  if (payload.errors != null && !Array.isArray(payload.errors)) return "invalid_errors";
  if (payload.events != null && !Array.isArray(payload.events)) return "invalid_events";

  return null;
}

function parseLimit(value) {
  if (value === undefined) return 100;
  if (typeof value !== "string" || !/^\d+$/.test(value)) return null;
  const limit = Number(value);
  return limit >= 1 && limit <= 1000 ? limit : null;
}

function isFiniteNumber(value) {
  return typeof value === "number" && Number.isFinite(value);
}

function isPlainObject(value) {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

module.exports = { createApp };
