import { readFileSync, existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { spawn } from 'node:child_process';

const backend = fileURLToPath(new URL('../backend/', import.meta.url));
const file = `${backend}.env`;
const env = { ...process.env };
if (existsSync(file)) {
  const lines = readFileSync(file, 'utf8').split(/\r?\n/);
  for (let index = 0; index < lines.length; index++) {
    const line = lines[index].trim();
    if (!line || line.startsWith('#')) continue;
    const match = /^(CARDTALK_[A-Z0-9_]+|PORT)\s*=\s*(.*)$/.exec(line);
    if (!match) throw new Error(`backend/.env 第 ${index + 1} 行应为 CARDTALK_* 或 PORT 的 KEY=value`);
    let value = match[2];
    if (value.startsWith('"') || value.startsWith("'")) {
      if (value.at(-1) !== value[0]) throw new Error(`backend/.env 第 ${index + 1} 行引号未闭合`);
      value = value.slice(1, -1);
    }
    // Environment supplied by the launching shell takes priority. No evaluation,
    // interpolation, shell sourcing or secret values in process arguments.
    if (env[match[1]] === undefined) env[match[1]] = value;
  }
}
const child = spawn('mvn', ['spring-boot:run'], { cwd: backend, env, stdio: 'inherit' });
child.on('error', () => { console.error('无法启动 Maven，请确认 mvn 和 JDK 21+ 已安装。'); process.exitCode = 1; });
for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => child.kill(signal));
child.on('exit', (code) => { process.exitCode = code ?? 1; });
