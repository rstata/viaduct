const fs = require('fs');
const path = require('path');

const COMMENT_MARKER = '<!-- viaduct-build-scan-comment -->';

function readLegacyScanEntry(baseDir, dir, file) {
  if (file === 'pr-number.txt' || !file.endsWith('.txt')) {
    return null;
  }

  const url = fs.readFileSync(path.join(baseDir, dir, file), 'utf8').trim();
  if (!url || url === 'null') {
    return null;
  }

  const jobName = file.replace('.txt', '');
  const matrixInfo = dir.replace('build-scan-urls-', '').replace(`${jobName}-`, '');
  return {
    label: `${jobName} (${matrixInfo})`,
    url,
  };
}

function collectBuildScanData(baseDir) {
  if (!fs.existsSync(baseDir)) {
    return {
      prNumber: null,
      scansByJob: new Map(),
    };
  }

  let prNumber = null;
  const scansByJob = new Map();
  const artifactDirs = fs.readdirSync(baseDir);

  for (const dir of artifactDirs) {
    const dirPath = path.join(baseDir, dir);
    if (!fs.statSync(dirPath).isDirectory()) {
      continue;
    }

    if (!prNumber) {
      const prFile = path.join(dirPath, 'pr-number.txt');
      if (fs.existsSync(prFile)) {
        const num = fs.readFileSync(prFile, 'utf8').trim();
        if (num && num !== 'null') {
          prNumber = parseInt(num, 10);
        }
      }
    }

    const files = fs.readdirSync(dirPath);
    const metadataFiles = files.filter((file) => file.endsWith('.json'));
    if (metadataFiles.length > 0) {
      for (const file of metadataFiles) {
        const metadata = JSON.parse(fs.readFileSync(path.join(dirPath, file), 'utf8'));
        if (!metadata.label || !metadata.url) {
          continue;
        }
        scansByJob.set(metadata.label, metadata.url);
      }
      continue;
    }

    for (const file of files) {
      const entry = readLegacyScanEntry(baseDir, dir, file);
      if (entry) {
        scansByJob.set(entry.label, entry.url);
      }
    }
  }

  return {prNumber, scansByJob};
}

function buildCommentBody({runUrl, scansByJob}) {
  let body = `${COMMENT_MARKER}\n## Gradle Build Scan URLs\n\n`;
  body += `The [Build and Test workflow](${runUrl}) produced Gradle build scans. Here are the build scan links for debugging:\n\n`;
  body += '| Job | Build Scan |\n';
  body += '|-----|------------|\n';
  for (const [job, url] of [...scansByJob.entries()].sort(([left], [right]) => left.localeCompare(right))) {
    body += `| ${job} | [View Build Scan](${url}) |\n`;
  }
  body += '\n---\n*Posted automatically by the post-build-scan-comments workflow.*';
  return body;
}

function findExistingComment(comments) {
  return comments.find((comment) =>
    comment.body?.includes(COMMENT_MARKER) ||
    (
      comment.user?.type === 'Bot' &&
      comment.body?.includes('## Gradle Build Scan URLs') &&
      comment.body?.includes('post-build-scan-comments workflow.')
    )
  );
}

module.exports = {
  COMMENT_MARKER,
  buildCommentBody,
  collectBuildScanData,
  findExistingComment,
};
