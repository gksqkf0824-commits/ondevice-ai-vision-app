import os
import json
import csv
import random
import shutil
import xml.etree.ElementTree as ET
from pathlib import Path
from collections import defaultdict, Counter

# ==============================
# 설정 (여기만 수정)
# ==============================
INPUT_DIR = r"C:\your_dataset"
OUTPUT_ROOT = r"C:\processed_dataset"
DATASET_NAME = "B10_dataset"
SAMPLE_SIZE = 5000
FILE_PREFIX = "B10"

# 클래스 매핑: scan_classes.py 결과 보고 여기만 채우면 됨
CLASS_MAP = {
    # 예시
    # "person": "person",
    # "car": "vehicle",
    # "automobile": "vehicle",
    # "bus": "bus",
    # "truck": "vehicle",
    # "bicycle": "bicycle",
    # "motorcycle": "vehicle",
    # "traffic light": "traffic_light",
    # "bench": "bench",
    # "chair": "chair",
    # "cup": "cup",
    # "mug": "cup",
    # "bottle": "bottle",
    # "cell phone": "cell_phone",
}

# YOLO 숫자 id -> 원본 클래스명 매핑
# scan 결과에 YOLO_ID_x 가 있으면 여기에 채우면 됨
YOLO_ID_TO_NAME = {
    # "0": "person",
    # "1": "bicycle",
    # "2": "car",
}

IMAGE_EXTS = [".jpg", ".jpeg", ".png", ".bmp", ".webp"]

# ==============================
# 출력 경로
# ==============================
OUTPUT_DIR = Path(OUTPUT_ROOT) / DATASET_NAME
IMG_DIR = OUTPUT_DIR / "images"
ANN_DIR = OUTPUT_DIR / "annotations"
META_DIR = OUTPUT_DIR / "metadata"

# ==============================
# 상태 저장
# ==============================
unknown_classes = set()
sampled_records = []
log_lines = []
kept_class_counter = Counter()

# ==============================
# 유틸
# ==============================
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

def normalize_class_name(cls_name: str) -> str:
    return str(cls_name).strip().lower()

def map_class(raw_cls: str):
    key = normalize_class_name(raw_cls)
    mapped = CLASS_MAP.get(key)
    if mapped is None:
        unknown_classes.add(raw_cls)
        return None
    return mapped

def find_image_candidates(root: Path):
    by_name = defaultdict(list)
    by_stem = defaultdict(list)

    for p in root.rglob("*"):
        if p.is_file() and p.suffix.lower() in IMAGE_EXTS:
            by_name[p.name].append(p)
            by_stem[p.stem].append(p)

    return by_name, by_stem

def resolve_image_path(file_name: str, image_by_name, image_by_stem):
    if not file_name:
        return None

    file_name = file_name.replace("\\", "/").split("/")[-1]

    # 1순위: 전체 파일명 정확히 일치
    if file_name in image_by_name and image_by_name[file_name]:
        return image_by_name[file_name][0]

    # 2순위: stem 기준
    stem = Path(file_name).stem
    if stem in image_by_stem and image_by_stem[stem]:
        return image_by_stem[stem][0]

    return None

def xyxy_to_xywh(xmin, ymin, xmax, ymax):
    x = float(xmin)
    y = float(ymin)
    w = float(xmax) - float(xmin)
    h = float(ymax) - float(ymin)
    return [x, y, w, h]

# ==============================
# 포맷별 파싱
# ==============================
def load_coco_items(json_path: Path):
    data = safe_read_json(json_path)
    if not data or not is_coco_json(data):
        return []

    cat_id_to_name = {}
    for c in data.get("categories", []):
        cid = c.get("id")
        name = c.get("name")
        if cid is not None and name is not None:
            cat_id_to_name[cid] = str(name)

    img_id_to_name = {}
    for img in data.get("images", []):
        iid = img.get("id")
        fname = img.get("file_name")
        if iid is not None and fname:
            img_id_to_name[iid] = str(fname)

    grouped = defaultdict(list)
    for ann in data.get("annotations", []):
        image_id = ann.get("image_id")
        category_id = ann.get("category_id")
        bbox = ann.get("bbox")

        if image_id not in img_id_to_name:
            continue
        if category_id not in cat_id_to_name:
            continue
        if not isinstance(bbox, list) or len(bbox) != 4:
            continue

        file_name = img_id_to_name[image_id]
        raw_cls = cat_id_to_name[category_id]
        grouped[file_name].append((raw_cls, [float(bbox[0]), float(bbox[1]), float(bbox[2]), float(bbox[3])]))

    items = []
    for file_name, objs in grouped.items():
        items.append({
            "image_file_name": file_name,
            "objects": objs,
            "source_ann": str(json_path),
            "format": "COCO_JSON",
        })
    return items

def load_custom_json_item(json_path: Path):
    data = safe_read_json(json_path)
    if not data or not is_custom_json(data):
        return None

    image_name = data.get("image", json_path.stem)
    objects = []

    for obj in data.get("objects", []):
        raw_cls = obj.get("class_name", obj.get("label", obj.get("name")))
        bbox = obj.get("bbox")

        if raw_cls is None or bbox is None or not isinstance(bbox, list) or len(bbox) != 4:
            continue

        objects.append((str(raw_cls), [float(bbox[0]), float(bbox[1]), float(bbox[2]), float(bbox[3])]))

    return {
        "image_file_name": str(image_name),
        "objects": objects,
        "source_ann": str(json_path),
        "format": "CUSTOM_JSON",
    }

def load_voc_item(xml_path: Path):
    try:
        tree = ET.parse(xml_path)
        root = tree.getroot()
    except Exception:
        return None

    file_name = None
    filename_tag = root.find("filename")
    if filename_tag is not None and filename_tag.text:
        file_name = filename_tag.text.strip()
    else:
        file_name = xml_path.stem

    objects = []
    for obj in root.findall("object"):
        name_tag = obj.find("name")
        bnd = obj.find("bndbox")
        if name_tag is None or bnd is None or name_tag.text is None:
            continue

        try:
            xmin = float(bnd.find("xmin").text)
            ymin = float(bnd.find("ymin").text)
            xmax = float(bnd.find("xmax").text)
            ymax = float(bnd.find("ymax").text)
        except Exception:
            continue

        bbox = xyxy_to_xywh(xmin, ymin, xmax, ymax)
        objects.append((name_tag.text.strip(), bbox))

    return {
        "image_file_name": file_name,
        "objects": objects,
        "source_ann": str(xml_path),
        "format": "VOC_XML",
    }

def load_yolo_item(txt_path: Path):
    file_name = txt_path.stem
    objects = []

    try:
        with open(txt_path, "r", encoding="utf-8") as f:
            lines = f.readlines()
    except Exception:
        return None

    for line in lines:
        parts = line.strip().split()
        if len(parts) != 5:
            continue

        cls_id = parts[0].strip()
        raw_cls = YOLO_ID_TO_NAME.get(cls_id)
        if raw_cls is None:
            raw_cls = f"YOLO_ID_{cls_id}"

        try:
            x = float(parts[1])
            y = float(parts[2])
            w = float(parts[3])
            h = float(parts[4])
        except Exception:
            continue

        # YOLO는 정규화 좌표 그대로 유지
        # 중간산출물 규격은 [x, y, w, h] 형식만 고정했지
        # 픽셀 절대좌표 강제 변환은 이미지 크기 필요 + YOLO 원본 체계라 불가
        objects.append((raw_cls, [x, y, w, h]))

    return {
        "image_file_name": file_name,
        "objects": objects,
        "source_ann": str(txt_path),
        "format": "YOLO_TXT",
    }

# ==============================
# 데이터셋 로드
# ==============================
def load_all_items(root: Path):
    items = []
    failed_ann = []

    for p in root.rglob("*"):
        if not p.is_file():
            continue

        suffix = p.suffix.lower()

        if suffix == ".json":
            data = safe_read_json(p)
            if data is None:
                failed_ann.append(str(p))
                continue

            if is_coco_json(data):
                sub_items = load_coco_items(p)
                items.extend(sub_items)
            elif is_custom_json(data):
                item = load_custom_json_item(p)
                if item:
                    items.append(item)
                else:
                    failed_ann.append(str(p))
            else:
                failed_ann.append(str(p))

        elif suffix == ".xml":
            item = load_voc_item(p)
            if item:
                items.append(item)
            else:
                failed_ann.append(str(p))

        elif suffix == ".txt":
            item = load_yolo_item(p)
            if item:
                items.append(item)
            else:
                failed_ann.append(str(p))

    return items, failed_ann

# ==============================
# 처리
# ==============================
def process_item(item, image_by_name, image_by_stem):
    src_image = resolve_image_path(item["image_file_name"], image_by_name, image_by_stem)
    if src_image is None:
        return None, "IMAGE_NOT_FOUND"

    new_objects = []
    for raw_cls, bbox in item["objects"]:
        mapped = map_class(raw_cls)
        if mapped is None:
            continue

        new_objects.append({
            "class_name": mapped,
            "bbox": bbox
        })
        kept_class_counter[mapped] += 1

    if not new_objects:
        return None, "NO_MAPPED_OBJECTS"

    return {
        "src_image": src_image,
        "src_image_name": src_image.name,
        "source_ann": item["source_ann"],
        "format": item["format"],
        "objects": new_objects,
    }, None

# ==============================
# 메인
# ==============================
def main():
    random.seed(42)

    IMG_DIR.mkdir(parents=True, exist_ok=True)
    ANN_DIR.mkdir(parents=True, exist_ok=True)
    META_DIR.mkdir(parents=True, exist_ok=True)

    image_by_name, image_by_stem = find_image_candidates(Path(INPUT_DIR))
    items, failed_ann = load_all_items(Path(INPUT_DIR))

    total_items = len(items)

    random.shuffle(items)
    if SAMPLE_SIZE > 0:
        items = items[:SAMPLE_SIZE]

    sampled_items = len(items)

    final_results = []
    drop_reason_counter = Counter()

    for item in items:
        result, reason = process_item(item, image_by_name, image_by_stem)
        if result is None:
            drop_reason_counter[reason] += 1
            continue
        final_results.append(result)

    # 저장
    for idx, result in enumerate(final_results):
        new_name = f"{FILE_PREFIX}_{idx:06d}"
        src_image = result["src_image"]
        new_image_path = IMG_DIR / f"{new_name}{src_image.suffix.lower()}"

        shutil.copy2(src_image, new_image_path)

        ann_payload = {
            "image": new_image_path.name,
            "objects": result["objects"]
        }

        with open(ANN_DIR / f"{new_name}.json", "w", encoding="utf-8") as f:
            json.dump(ann_payload, f, ensure_ascii=False, indent=2)

        sampled_records.append([
            str(src_image),
            result["source_ann"],
            new_image_path.name,
            f"{new_name}.json",
            result["format"],
            len(result["objects"])
        ])

    # metadata/class_map.json
    normalized_class_map = {normalize_class_name(k): v for k, v in CLASS_MAP.items()}
    with open(META_DIR / "class_map.json", "w", encoding="utf-8") as f:
        json.dump(normalized_class_map, f, ensure_ascii=False, indent=2)

    # metadata/sampled_files.csv
    with open(META_DIR / "sampled_files.csv", "w", newline="", encoding="utf-8-sig") as f:
        writer = csv.writer(f)
        writer.writerow([
            "original_image_path",
            "source_annotation_path",
            "new_image_name",
            "new_annotation_name",
            "source_format",
            "kept_object_count"
        ])
        writer.writerows(sampled_records)

    # metadata/preprocess_log.txt
    log_lines.append("===== PREPROCESS LOG =====")
    log_lines.append(f"INPUT_DIR: {INPUT_DIR}")
    log_lines.append(f"OUTPUT_DIR: {OUTPUT_DIR}")
    log_lines.append(f"TOTAL_PARSED_ITEMS: {total_items}")
    log_lines.append(f"SAMPLED_ITEMS: {sampled_items}")
    log_lines.append(f"FINAL_USED_ITEMS: {len(final_results)}")
    log_lines.append("")
    log_lines.append("===== DROP REASONS =====")
    if drop_reason_counter:
        for k, v in drop_reason_counter.items():
            log_lines.append(f"{k}: {v}")
    else:
        log_lines.append("(none)")
    log_lines.append("")
    log_lines.append("===== KEPT CLASSES =====")
    if kept_class_counter:
        for cls_name, cnt in kept_class_counter.most_common():
            log_lines.append(f"{cls_name}: {cnt}")
    else:
        log_lines.append("(none)")
    log_lines.append("")
    log_lines.append("===== UNMAPPED CLASSES =====")
    if unknown_classes:
        for cls_name in sorted(unknown_classes):
            log_lines.append(f"- {cls_name}")
    else:
        log_lines.append("(none)")
    log_lines.append("")
    log_lines.append("===== FAILED / UNKNOWN ANNOTATION FILES =====")
    if failed_ann:
        for p in failed_ann[:300]:
            log_lines.append(p)
        if len(failed_ann) > 300:
            log_lines.append(f"... and {len(failed_ann) - 300} more")
    else:
        log_lines.append("(none)")

    with open(META_DIR / "preprocess_log.txt", "w", encoding="utf-8") as f:
        f.write("\n".join(log_lines))

    print("완료")
    print(f"출력 경로: {OUTPUT_DIR}")

if __name__ == "__main__":
    main()