# Obstacle datasets

The detector is trained by `training/fetch_v2.py`, merged by `training/build_dataset.py`, and fit by `training/train_obstacles.py`. Pictures are not committed. The class list is `training/class_map.py`.

Indices 0 through 28 are the classes already spoken by the app. Index 29 is `wall`, for a large flat obstacle. `unknown object` is not a trained class. Depth still speaks it when no box explains a close region, and it still speaks a wall the detector missed.

A source is used only when its license allows commercial use and redistribution of a model trained on it. Share-alike, research-only, and non-commercial terms were left out so the shipped weights do not pick up those duties.

## Used

### Open Images (boxes from v6, class names from v7)

- Labels: [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/). [Open Images](https://storage.googleapis.com/openimages/web/index.html), [class descriptions](https://storage.googleapis.com/openimages/v7/oidv7-class-descriptions-boxable.csv), [train boxes](https://storage.googleapis.com/openimages/v6/oidv6-train-annotations-bbox.csv).
- Photos: listed as [CC BY 2.0](https://creativecommons.org/licenses/by/2.0/). The [dataset README](https://github.com/openimages/dataset/blob/main/READMEV3.md) says to verify each photo. The image files are not redistributed with the app.
- Mapped into the app: person (and man, woman, boy, girl), bicycle, motorcycle, car (and taxi, ambulance), bus, truck (and van), cow (cattle, bull), dog, cat, handcart (cart), stairs, pole (street light), street furniture (bench, fire hydrant, traffic sign), tree (and palm), chair, couch (and studio couch, sofa bed), dining table (table, coffee table, desk, kitchen table), bed (and infant bed), toilet, potted plant (houseplant), refrigerator, television, door.
- Household obstacles added for walking indoors: shoe (footwear, boot, sandal, high heels), bag (handbag, backpack, luggage, suitcase, briefcase), toy (toy, doll, teddy bear), bin (waste container), appliance (microwave, oven, washing machine, dishwasher, toaster, gas stove, kitchen appliance), cabinet (chest of drawers, wardrobe, bookcase, filing cabinet, nightstand).
- Building, office building, and billboard become `wall` only when the box is large and reaches the lower part of the frame. A distant skyline is not labeled as a wall in the path.

### COCO-Stuff (walls and fences)

- Stuff labels: [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/). [COCO-Stuff](https://github.com/nightrome/cocostuff), [stuff_annotations_trainval2017.zip](http://images.cocodataset.org/annotations/stuff_annotations_trainval2017.zip).
- The photos are the COCO 2017 images. [COCO terms](https://cocodataset.org/#termsofuse) say the annotation data is CC BY 4.0 and the photos come from Flickr under those photographers' terms. They are not all cleared for every commercial use. The app already said this on the About screen. The photos are not shipped. Only `wall-*` regions (as `wall`) and `fence` (as `barrier`) are kept, and a wall box has to be a large flat region.

### ROD sidewalk set

- [Abtinzandi/Obstacle-Detection-Dataset-YOLO](https://huggingface.co/datasets/Abtinzandi/Obstacle-Detection-Dataset-YOLO), [MIT](https://opensource.org/license/mit).
- The card says the release merges Roboflow Universe sets with the authors' own street photos and is published as MIT. Upstream project licenses are not itemized on the card. The file we download carries MIT, which allows commercial use and redistribution of trained weights.
- Building boxes that pass the wall filter become `wall`. Dustbin becomes `bin`. Manhole, stairs, pole, vehicles, and street furniture map onto the existing names. The authors' test split stays in our test split.

### AssistEye unified obstacles (curb, pole, pothole, drain)

- [Dderedor/obstacles_on_the_road](https://huggingface.co/datasets/Dderedor/obstacles_on_the_road), [MIT](https://opensource.org/license/mit).
- The publisher describes `bump` as бордюр / protrusion, so those boxes are `curb`. `hole` is described as a crack and is dropped. `speed_bump` is not a class. Waste containers are `bin`.
- The card says the set merges [Urban Community Issues](https://doi.org/10.34740/kaggle/dsv/14742590) and [Obstacles Avoidance Assistance for Visually Impaired](https://doi.org/10.17632/xwhnp82rhk). Those upstream pages do not restate a license next to the Hugging Face MIT grant. The copy used here is the MIT release.

### Potholes

- [keremberke/pothole-segmentation](https://huggingface.co/datasets/keremberke/pothole-segmentation), from [Roboflow pothole-detection-irkz9](https://universe.roboflow.com/imacs-pothole-detection-wo8mu/pothole-detection-irkz9). The dataset card says [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/).

### Auto rickshaw and cycle rickshaw

- [shravya11/tuk-tuk-detection](https://huggingface.co/datasets/shravya11/tuk-tuk-detection), [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/).
- Used: the Roboflow tuk-tuk export, the rickshaw export, and the Datacluster auto-rickshaw folder. The card lists those three as CC BY 4.0.
- Not used: `google_search_auto` and `google_search_tuktuk`. The card says their license is unknown, and they have no boxes.

## Not used

| Source | Why |
| --- | --- |
| RDD2022 | [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/). Commercial use of the pictures is allowed, but share-alike can attach to a model. Left out so the weights stay free of that duty. Potholes come from the CC BY set above instead. |
| BDD100K, nuScenes, ExDark, Cityscapes, KITTI, Mapillary, ADE20K, ScanNet, Matterport | Research or non-commercial terms. |
| Ryukijano/Pothole-detection-Yolov8 | Roboflow note says CC BY 4.0. The Hub license field says OpenRAIL. The two disagree, so it was not used. |
| thirdeyelabs/indian-road-dataset | CC BY 4.0, but the Hub repo is gated and this environment has no token. |
| OAODD (danaaaaa3/OAODD) | CC BY 4.0, and the classes fit a sidewalk. The Hub repo has label files and no images. |
| SafeWalkBD | CC BY 4.0 on Roboflow, with curb, wall, bollard, and rickshaw. The export sits behind a Roboflow key and a Cloudflare challenge, so the files were not downloaded. |
| JATAYU | No commercial license is stated on the download. |
| RampNet / Project Sidewalk pictures | Code is MIT. The pictures are Google Street View and were not downloaded. |
| A Roboflow night-vision reupload | CC BY 4.0 on the project page, source license of the photos not confirmed. Same decision as the previous low-light note. |

Low-light numbers are the held-out test pictures after dimming, gamma, noise, and motion blur (`training/lowlight.py`). Real night sets with a non-commercial license were not added. Training also darkens about 40% of each batch so the fit sees that shift.

## Private synthetic batch (not in the repo)

`synth160.zip` is a local AI-generated, hand-labeled set: Indian footpath scenes and household rooms. The pictures and label files are not committed. `training/import_synth160.py` reads the zip from outside the repo.

Indices 0 through 28 match `training/convert_obstacles.py`. Index 29 is `wall`. Index 30, `unknown_obstacle`, is not a detector class. The file uses that id for construction debris, sand, hanging wires, and also for household things (shoes, bags, toys, a washer) that were not given their own name. Those boxes are dropped. Depth still speaks an unknown object when no trained box explains a close region. An image whose boxes are all `unknown_obstacle` is left out.

The kept images are added only to the train split of the with-synthetic run. Validation and test stay the real photographs, including the indoor held-out pictures. The ablation trains the same YOLO11n recipe with and without this train-only batch and scores both on those real photos.

## Collect mode

`training/finetune_collected.py` fine-tunes on a folder of JPEGs plus JSON sidecars from Collect mode. Labels use the class names in `training/class_map.py`, including `shoe`, `bag`, `toy`, `bin`, `appliance`, `cabinet`, `chair`, `couch`, `dining table`, `bed`, `door`, `stairs`, and `wall`. That is how later walks inside the house add the rooms the public sets still miss.

```bash
python3 training/fetch_v2.py all
python3 training/build_dataset.py
python3 training/train_obstacles.py
python3 training/evaluate_compare.py --candidate training/metrics/obstacles_v2_int8.tflite --install
python3 training/finetune_collected.py --photos /path/to/collected --dry-run
```

There is no GPU on this machine and no self-hosted GPU worker was connected. The shipped `obstacles_v1.tflite` file is int8 and cannot be a training checkpoint. `train_obstacles.py` therefore fine-tunes `yolo11n.pt`, the same YOLO11n architecture, on CPU when CUDA is absent. Input size stays 320. The run is capped at 8 epochs or 2.5 hours, and Ultralytics writes a checkpoint every epoch. The head grows past the COCO checkpoint for `wall` and the household names (`shoe`, `bag`, `toy`, `bin`, `appliance`, `cabinet`). The backbone still matches the current app model. `evaluate_compare.py` writes per-class indoor scores, including low light, separately from the street mean. The int8 TFLite export is copied into the app only when that script finds it better on the shared classes and not meaningfully worse in the darkened test.
