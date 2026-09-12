#!/usr/bin/env python3
"""Fail unless every reactor module resolves in the registry at VERSION.

A green tag push must imply the artifacts shipped. cashu-mint#409 and
398ja/cashu-lib#258 were both "workflow passed, nothing published", and 0.34.2
went out missing every cashu-mint-admin module.

The module list is walked from the root pom's <modules>, transitively. Three
earlier approaches were tried and rejected against the real repository:

  * `exec:exec` -- no exec-maven-plugin version is declared here, so the check
    meant to make releases trustworthy would itself resolve whatever was newest.
  * parsing "Uploading to" from the deploy log -- silently produced nothing,
    because MAVEN_ARGS=-ntp is set in the runner environment and suppresses
    those lines. That is how v0.36.2 failed this step while all 14 artifacts
    had in fact published.
  * directory names -- reports a false positive for cashu-mint-admin, an
    aggregator whose artifactId is `cashu-mint` and which the reactor does not
    build.

Reading <modules> depends on nothing outside the checkout.
"""
import os
import sys
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET

NS = {"m": "http://maven.apache.org/POM/4.0.0"}
BASE = "https://maven.398ja.xyz/releases/xyz/tcheeric"


def reactor_artifacts(pom="pom.xml"):
    """Every artifactId the reactor builds, root first, depth-first."""
    root = ET.parse(pom).getroot()
    here = os.path.dirname(pom)
    yield root.findtext("m:artifactId", namespaces=NS)
    for module in root.findall("m:modules/m:module", NS):
        yield from reactor_artifacts(os.path.join(here, module.text, "pom.xml"))


def resolves(url):
    request = urllib.request.Request(url, method="HEAD")
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return response.status
    except urllib.error.HTTPError as error:
        return error.code
    except OSError as error:
        return f"unreachable ({error})"


def main():
    version = os.environ.get("VERSION", "").lstrip("v")
    if not version:
        print("::error::VERSION is empty; refusing to report success")
        return 1

    artifacts = list(reactor_artifacts())
    if not artifacts:
        print("::error::no reactor modules found; refusing to report success")
        return 1

    print(f"verifying {len(artifacts)} reactor modules at {version}")
    failed = 0
    for artifact in artifacts:
        url = f"{BASE}/{artifact}/{version}/{artifact}-{version}.pom"
        status = resolves(url)
        if status == 200:
            print(f"  ok    {artifact}")
        else:
            print(f"::error::{artifact} {version} not published (HTTP {status}) {url}")
            failed += 1

    if failed:
        print(f"::error::{failed} of {len(artifacts)} modules did not publish")
        return 1
    print(f"all {len(artifacts)} modules resolve at {version}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
