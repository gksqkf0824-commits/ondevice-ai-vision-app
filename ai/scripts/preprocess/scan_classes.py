import os
import json
import xml.etree.ElementTree as ET
from pathlib import Path
from collections import Counter, defaultdict

# ==============================
# 설정
# ==============================
INPUT_DIR = r"C:\your_dataset"
OUTPUT_REPORT = r"C:\your_dataset_class_scan.txt"

IMAGE_EXTS = {".jpg", ".jpeg", ".png", ".bmp", ".webp"}
ANN_EXTS = {".json", ".xml", ".txt"}

# ==============================
# 유틸
# ==============================
def is_coco_json(data: dict) -> bool:
    return (
        isinstance(data, dict)
        and "images" in data
        and "annotations" in data
        and "categories" in data
        and isinstance(data["images"], list)
        and isinstance(data["annotations"], list)
        and isinstance(data["categories"], list)
    )

def is_custom_json(data: dict) -> bool:
    return isinstance(data, dict) and "objects" in data and isinstance(data["objects"], list)

def safe_read_json(path: Path):
    try:
        with open(path, "r", encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        try:
            with open(path, "r", encoding="utf-8-sig") as f:
                return json.load(f)
        except Exception:
            return None

def parse_coco_classes(path: Path, class_counter: Counter, file_counter: Counter):
    data = safe_read_json(path)
    if not data or not is_coco_json(data):
        return False

    cat_id_to_name = {}
    for c in data.get("categories", []):
        cid = c.get("id")
        name = str(c.get("name", "")).strip()
        if cid is not None and name:
            cat_id_to_name[cid] = name

    local_counter = Counter()
    for ann in data.get("annotations", []):
        cid = ann.get("category_id")
        cls_name = cat_id_to_name.get(cid)
        if cls_name:
            class_counter[cls_name] += 1
            local_counter[cls_name] += 1

    for cls_name in local_counter:
        file_counter[cls_name] += 1

    return True

def parse_custom_json_classes(path: Path, class_counter: Counter, file_counter: Counter):
    data = safe_read_json(path)
    if not data or not is_custom_json(data):
        return False

    local_counter = Counter()
    for obj in data.get("objects", []):
        cls = obj.get("class_name")
        if cls is None:
            cls = obj.get("label")
        if cls is None:
            cls = obj.get("name")
        if cls is None:
            continue
        cls = str(cls).strip()
        if not cls:
            continue
        class_counter[cls] += 1
        local_counter[cls] += 1

    for cls_name in local_counter:
        file_counter[cls_name] += 1

    return True

def parse_voc_classes(path: Path, class_counter: Counter, file_counter: Counter):
    try:
        tree = ET.parse(path)
        root = tree.getroot()
    except Exception:
        return False

    local_counter = Counter()
    found = False

    for obj in root.findall("object"):
        name_tag = obj.find("name")
        if name_tag is None or name_tag.text is None:
            continue
        cls = name_tag.text.strip()
        if not cls:
            continue
        class_counter[cls] += 1
        local_counter[cls] += 1
        found = True

    for cls_name in local_counter:
        file_counter[cls_name] += 1

    return found

def parse_yolo_txt_classes(path: Path, class_counter: Counter, file_counter: Counter, yolo_id_counter: Counter):
    try:
        with open(path, "r", encoding="utf-8") as f:
            lines = f.readlines()
    except Exception:
        return False

    local_counter = Counter()
    found = False

    for line in lines:
        parts = line.strip().split()
        if len(parts) < 5:
            continue
        cls_id = parts[0].strip()
        if not cls_id:
            continue
        key = f"YOLO_ID_{cls_id}"
        class_counter[key] += 1
        local_counter[key] += 1
        yolo_id_counter[cls_id] += 1
        found = True

    for cls_name in local_counter:
        file_counter[cls_name] += 1

    return found

def main():
    root = Path(INPUT_DIR)
    all_files = list(root.rglob("*"))

    image_files = [p for p in all_files if p.is_file() and p.suffix.lower() in IMAGE_EXTS]
    ann_files = [p for p in all_files if p.is_file() and p.suffix.lower() in ANN_EXTS]

    class_counter = Counter()
    file_counter = Counter()
    yolo_id_counter = Counter()

    format_stats = defaultdict(int)
    failed_files = []

    for path in ann_files:
        suffix = path.suffix.lower()

        parsed = False

        if suffix == ".json":
            if parse_coco_classes(path, class_counter, file_counter):
                format_stats["COCO_JSON"] += 1
                parsed = True
            elif parse_custom_json_classes(path, class_counter, file_counter):
                format_stats["CUSTOM_JSON"] += 1
                parsed = True

        elif suffix == ".xml":
            if parse_voc_classes(path, class_counter, file_counter):
                format_stats["VOC_XML"] += 1
                parsed = True

        elif suffix == ".txt":
            if parse_yolo_txt_classes(path, class_counter, file_counter, yolo_id_counter):
                format_stats["YOLO_TXT"] += 1
                parsed = True

        if not parsed:
            failed_files.append(str(path))

    lines = []
    lines.append("===== DATASET CLASS SCAN REPORT =====")
    lines.append(f"INPUT_DIR: {INPUT_DIR}")
    lines.append(f"TOTAL_IMAGE_FILES: {len(image_files)}")
    lines.append(f"TOTAL_ANNOTATION_FILES: {len(ann_files)}")
    lines.append("")
    lines.append("===== DETECTED ANNOTATION FORMATS =====")
    if format_stats:
        for k, v in sorted(format_stats.items()):
            lines.append(f"{k}: {v}")
    else:
        lines.append("(none)")
    lines.append("")
    lines.append("===== DETECTED CLASSES (by object count) =====")
    if class_counter:
        for cls_name, cnt in class_counter.most_common():
            lines.append(f"{cls_name}: {cnt} objects, {file_counter[cls_name]} files")
    else:
        lines.append("(none)")
    lines.append("")
    lines.append("===== YOLO RAW CLASS IDS =====")
    if yolo_id_counter:
        for cls_id, cnt in sorted(yolo_id_counter.items(), key=lambda x: int(x[0]) if x[0].isdigit() else x[0]):
            lines.append(f"class_id {cls_id}: {cnt} objects")
        lines.append("")
        lines.append("※ YOLO_ID_x 형태는 classes.txt / data.yaml names 매핑이 없어서 이름을 모르는 상태임")
    else:
        lines.append("(none)")
    lines.append("")
    lines.append("===== FAILED / UNKNOWN ANNOTATION FILES =====")
    if failed_files:
        for p in failed_files[:200]:
            lines.append(p)
        if len(failed_files) > 200:
            lines.append(f"... and {len(failed_files) - 200} more")
    else:
        lines.append("(none)")

    os.makedirs(Path(OUTPUT_REPORT).parent, exist_ok=True)
    with open(OUTPUT_REPORT, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))

    print(f"완료: {OUTPUT_REPORT}")

if __name__ == "__main__":
    main()