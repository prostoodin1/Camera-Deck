"""Publish an assembled Camera Deck prerelease in its public project repository.

Requires a clean, pushed source commit. No credentials are printed or persisted.
The draft becomes visible only after all uploaded asset hashes are verified.
"""
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import urllib.request
from urllib.parse import quote

project=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(project/"desktop"))
import updates


def main():
    version=json.loads((project/"version.json").read_text())
    tag="camera-deck-v"+version["version"]
    token=updates.github_token()
    def request(path,method="GET",body=None):
        data=None if body is None else json.dumps(body).encode()
        req=urllib.request.Request(updates.API+path,data=data,method=method,headers={
            "Authorization":"Bearer "+token,"Accept":"application/vnd.github+json",
            "Content-Type":"application/json","User-Agent":"CameraDeck-Release"})
        with urllib.request.urlopen(req,timeout=60) as result: return json.load(result)
    repo=request("")
    if repo["full_name"]!="prostoodin1/Camera-Deck" or repo["private"]:
        raise RuntimeError("Refusing to publish outside public Camera-Deck")
    dirty=subprocess.check_output(["git","status","--porcelain"],cwd=project,text=True)
    if dirty.strip(): raise RuntimeError("Commit the completed source before publishing")
    commit=subprocess.check_output(["git","rev-parse","HEAD"],cwd=project,text=True).strip()
    out=project/"release"/version["version"]
    source=out/f"CameraDeck-Source-{version['version']}.zip"
    subprocess.run(["git","archive","--format=zip","--output",str(source),"HEAD"],cwd=project,check=True)
    checks=[]
    for path in sorted(out.iterdir()):
        if path.is_file() and path.name!="SHA256SUMS.txt":
            with path.open("rb") as f: checks.append(hashlib.file_digest(f,"sha256").hexdigest()+"  "+path.name)
    (out/"SHA256SUMS.txt").write_text("\n".join(checks)+"\n")
    envelope=(out/"camera-deck-update.json").read_bytes()
    manifest=updates.verify_envelope(envelope,current_code=0)
    if manifest["code"]!=version["code"]: raise RuntimeError("Release version mismatch")
    for platform in ("android","windows"):
        artifact=out/manifest[platform]["name"]
        with artifact.open("rb") as f: digest=hashlib.file_digest(f,"sha256").hexdigest()
        if digest!=manifest[platform]["sha256"] or artifact.stat().st_size!=manifest[platform]["size"]:
            raise RuntimeError("Artifact differs from signed manifest")
    body=(project/"RELEASE_NOTES.md").read_text(encoding="utf-8")
    release=request("/releases","POST",{"tag_name":tag,"target_commitish":commit,"name":"Camera Deck "+version["version"]+" · Android + Windows preview","body":body,"draft":True,"prerelease":True})
    for path in sorted(out.iterdir()):
        if not path.is_file(): continue
        req=urllib.request.Request(f"https://uploads.github.com/repos/prostoodin1/Camera-Deck/releases/{release['id']}/assets?name="+quote(path.name),
            data=path.read_bytes(),method="POST",headers={"Authorization":"Bearer "+token,"Content-Type":"application/octet-stream","User-Agent":"CameraDeck-Release"})
        with urllib.request.urlopen(req,timeout=180) as response: asset=json.load(response)
        with path.open("rb") as f: digest="sha256:"+hashlib.file_digest(f,"sha256").hexdigest()
        if asset.get("digest")!=digest or asset["size"]!=path.stat().st_size: raise RuntimeError("Uploaded asset hash mismatch; left release as draft")
        print("Verified asset:",path.name)
    release=request(f"/releases/{release['id']}","PATCH",{"draft":False,"prerelease":True})
    print(release["html_url"])


if __name__=="__main__": main()
