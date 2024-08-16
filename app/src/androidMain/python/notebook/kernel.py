import json
import os
import runpy
import sys


def main(intent):
    os.chdir(intent.getStringExtra("jupyter_cwd"))
    connection_filename = intent.getStringExtra("jupyter_connection_file")
    print("Connection file: " +  # Use compact representation for log.
          json.dumps(json.load(open(connection_filename))).strip())

    # The kernel redirects standard streams to the notebook, and does not clean up after
    # itself.
    stdin, stdout, stderr = sys.stdin, sys.stdout, sys.stderr
    try:
        sys.argv[1:] = ["-f", connection_filename]
        runpy.run_module("ipykernel_launcher", run_name="__main__")
    except SystemExit as e:
        if e.code == 0:
            pass
    finally:
        sys.stdin, sys.stdout, sys.stderr = stdin, stdout, stderr
