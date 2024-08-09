from __future__ import annotations
import traceback

try:
    from .modifier import Modifier
    modifier = Modifier()
    from .alignment import Alignment, AbsoluteAlignment


except Exception as e:
    print("-----------------------------------------------------------------------------------------------------------")
    traceback.print_exc()
    print("ERROR: PyComposeUI Ui Library is not Found.")
    print("-----------------------------------------------------------------------------------------------------------")
    raise e
