"""Publish immutable source archives for the explicitly recorded version commits."""
import json
import os
from pathlib import Path
import subprocess
import tempfile

def run(args):
    subprocess.run(args, check=True)

repo = os.environ['GITHUB_REPOSITORY']
for release in json.loads(Path('release-index.json').read_text(encoding='utf-8')):
    tag, sha = release['tag'], release['sha']
    existing = subprocess.run(['gh', 'release', 'view', tag, '--repo', repo], capture_output=True)
    if existing.returncode == 0:
        print('Release already exists; leaving it unchanged:', tag)
        continue
    with tempfile.TemporaryDirectory() as directory:
        archive = Path(directory) / ('Phone-Archive-' + tag + '.zip')
        notes = Path(directory) / 'notes.md'
        notes.write_text(release['notes'], encoding='utf-8')
        run(['git', 'archive', '--format=zip', '--prefix=phone-archive/', '--output=' + str(archive), sha])
        args = ['gh', 'release', 'create', tag, str(archive), '--repo', repo,
                '--target', sha, '--title', release['title'], '--notes-file', str(notes), '--latest=false']
        if release['prerelease']:
            args.append('--prerelease')
        run(args)
