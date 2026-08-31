const path = require("node:path");
const { createApp } = require("./app");
const { openDatabase } = require("./database");

const host = process.env.HOST || "0.0.0.0";
const port = parsePort(process.env.PORT);
const apiKey = process.env.API_KEY || "change_me_api_key_2026";
const dbPath = process.env.DB_PATH
  ? path.resolve(process.env.DB_PATH)
  : path.resolve(__dirname, "..", "data", "bms.sqlite");

const database = openDatabase(dbPath);
const app = createApp({ database, apiKey });
const server = app.listen(port, host, () => {
  const displayHost = host === "0.0.0.0" || host === "::" ? "localhost" : host;
  console.log(`Сервер телеметрии запущен: http://${displayHost}:${port}`);
  console.log(`Проверка: http://${displayHost}:${port}/health`);
  console.log(`База данных: ${dbPath}`);
});

let shuttingDown = false;
function shutdown(signal) {
  if (shuttingDown) return;
  shuttingDown = true;
  console.log(`Получен ${signal}, сервер останавливается...`);

  server.close((error) => {
    database.close();
    if (error) {
      console.error("Ошибка остановки сервера:", error);
      process.exitCode = 1;
    }
  });
}

server.on("error", (error) => {
  if (!shuttingDown) database.close();
  console.error("Не удалось запустить сервер:", error);
  process.exitCode = 1;
});

process.on("SIGINT", () => shutdown("SIGINT"));
process.on("SIGTERM", () => shutdown("SIGTERM"));

function parsePort(value) {
  if (value === undefined || value === "") return 3000;
  const parsed = Number(value);
  if (!Number.isInteger(parsed) || parsed < 1 || parsed > 65535) {
    throw new Error("PORT должен быть целым числом от 1 до 65535");
  }
  return parsed;
}
