const assert = require('node:assert/strict');
const fs = require('fs');
const os = require('os');
const path = require('path');
const test = require('node:test');

const {
  COMMENT_MARKER,
  buildCommentBody,
  collectBuildScanData,
  findExistingComment,
} = require('../post_build_scan_comments');

function makeTempDir() {
  return fs.mkdtempSync(path.join(os.tmpdir(), 'build-scan-comments-'));
}

test('collectBuildScanData reads explicit metadata labels', () => {
  const tempdir = makeTempDir();
  const artifactDir = path.join(tempdir, 'build-scan-urls-test-21-ubuntu-latest');
  fs.mkdirSync(artifactDir, {recursive: true});
  fs.writeFileSync(path.join(artifactDir, 'pr-number.txt'), '42\n');
  fs.writeFileSync(
    path.join(artifactDir, 'test.json'),
    JSON.stringify({
      label: 'Test (Java 21, ubuntu-latest)',
      url: 'https://scans.gradle.com/s/abc123',
    }),
  );

  const {prNumber, scansByJob} = collectBuildScanData(tempdir);

  assert.equal(prNumber, 42);
  assert.equal(scansByJob.get('Test (Java 21, ubuntu-latest)'), 'https://scans.gradle.com/s/abc123');
});

test('collectBuildScanData falls back to legacy text file parsing', () => {
  const tempdir = makeTempDir();
  const artifactDir = path.join(tempdir, 'build-scan-urls-build-21-ubuntu-latest');
  fs.mkdirSync(artifactDir, {recursive: true});
  fs.writeFileSync(path.join(artifactDir, 'build-assemble.txt'), 'https://gradle.com/s/legacy123\n');

  const {scansByJob} = collectBuildScanData(tempdir);

  assert.equal(
    scansByJob.get('build-assemble (build-21-ubuntu-latest)'),
    'https://gradle.com/s/legacy123',
  );
});

test('buildCommentBody includes marker and sorts rows by label', () => {
  const scansByJob = new Map([
    ['Zed', 'https://gradle.com/s/zed'],
    ['Alpha', 'https://gradle.com/s/alpha'],
  ]);

  const body = buildCommentBody({
    runUrl: 'https://github.com/example/repo/actions/runs/123',
    scansByJob,
  });

  assert.match(body, new RegExp(COMMENT_MARKER.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')));
  assert.ok(body.indexOf('| Alpha | [View Build Scan](https://gradle.com/s/alpha) |') <
    body.indexOf('| Zed | [View Build Scan](https://gradle.com/s/zed) |'));
});

test('findExistingComment matches new marker-based comments', () => {
  const comment = findExistingComment([
    {id: 1, body: 'other', user: {type: 'Bot'}},
    {id: 2, body: `${COMMENT_MARKER}\n## Gradle Build Scan URLs`, user: {type: 'Bot'}},
  ]);

  assert.equal(comment.id, 2);
});

test('findExistingComment matches legacy bot comments', () => {
  const comment = findExistingComment([
    {
      id: 3,
      body: '## Gradle Build Scan URLs\n\n---\n*Posted automatically by the post-build-scan-comments workflow.*',
      user: {type: 'Bot'},
    },
  ]);

  assert.equal(comment.id, 3);
});
