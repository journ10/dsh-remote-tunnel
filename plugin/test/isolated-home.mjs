/** Every test process gets private, disposable config; never read the user's DSH home. */
import { mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const testHome = mkdtempSync(join(tmpdir(), 'dsh-plugin-test-'))
process.env.DSH_HOME = testHome
process.on('exit', () => rmSync(testHome, { recursive: true, force: true }))
