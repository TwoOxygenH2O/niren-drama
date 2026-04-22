import sys
import re

fpath = r"backend\src\main\java\com\niren\drama\entity\Script.java"
with open(fpath, "r", encoding="utf-8") as f:
    text = f.read()

print("Checking Script.java...")
print("HAS Data?", "@Data" in text)
