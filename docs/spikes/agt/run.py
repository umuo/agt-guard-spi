"""在临时目录编译本地源码并执行 spike，不修改 AGT 或 Maven 仓库。"""

import argparse
from pathlib import Path
import subprocess
import tempfile
import xml.etree.ElementTree as ET
import os


def compile_sources(sources, destination, release, classpath):
    command = ["javac", "--release", str(release), "-encoding", "UTF-8", "-d", str(destination)]
    if classpath:
        command.extend(["-cp", classpath])
    subprocess.run(command + [str(source) for source in sources], check=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--agt-root", type=Path, required=True)
    parser.add_argument("--m2", type=Path, help="已有 AGT 依赖的本地 Maven 缓存，默认 AGT_ROOT/.cache/m2")
    options = parser.parse_args()
    agt_root = options.agt_root.resolve()
    cache = options.m2 or agt_root / ".cache/m2"
    namespace = {"m": "http://maven.apache.org/POM/4.0.0"}
    pom = ET.parse(agt_root / "pom.xml").getroot()
    version = pom.find("m:properties/m:jackson.version", namespace).text
    bom_path = cache / f"com/fasterxml/jackson/jackson-bom/{version}/jackson-bom-{version}.pom"
    bom = ET.parse(bom_path).getroot()
    annotations_version = bom.find("m:properties/m:jackson.version.annotations", namespace).text
    coordinates = [
        ("com/fasterxml/jackson/core", "jackson-core", version),
        ("com/fasterxml/jackson/core", "jackson-databind", version),
        ("com/fasterxml/jackson/core", "jackson-annotations", annotations_version),
        ("com/fasterxml/jackson/dataformat", "jackson-dataformat-yaml", version),
    ]
    jars = [cache / group / artifact / release / f"{artifact}-{release}.jar"
            for group, artifact, release in coordinates]
    # 本 spike 使用 Java Builder，不读取 YAML，因此无需加载 SnakeYAML。
    for jar in jars:
        if not jar.is_file():
            raise FileNotFoundError(jar)
    project = Path(__file__).resolve().parents[3]
    with tempfile.TemporaryDirectory(prefix="agt-guard-spike-") as temporary:
        classes = Path(temporary)
        classpath = os.pathsep.join(str(path) for path in [classes, *jars])
        for module in ("agent-guard-spec", "agent-guard-core"):
            compile_sources(sorted((project / module / "src/main/java").rglob("*.java")),
                            classes, 8, classpath)
        compile_sources(sorted((agt_root / "agt-core/src/main/java").rglob("*.java")),
                        classes, 17, classpath)
        compile_sources([Path(__file__).with_name("AgtBridgeSpike.java")], classes, 17, classpath)
        subprocess.run(["java", "-cp", classpath, "AgtBridgeSpike"], check=True)


if __name__ == "__main__":
    main()
