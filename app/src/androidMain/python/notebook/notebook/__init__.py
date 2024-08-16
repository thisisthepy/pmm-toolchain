import binascii
import os
import re
import sys
import time

from android.content import Intent
from com.chaquo.python import Python
from com.chaquo.python.jupyter import KernelService
from java.lang import Integer
from jupyter_client import ioloop
from notebook import auth, notebookapp


SHUTDOWN_TIMEOUT = 2

app = Python.getPlatform().getApplication()


def main():
    files_dir = str(app.getFilesDir())
    os.chdir(files_dir)  # This will the the starting directory of the file browser.

    try:
        with open("password.txt") as password_file:
            password = password_file.read().strip()
    except OSError:
        password = binascii.hexlify(os.urandom(4)).decode("ascii")
        with open("password.txt", "w") as password_file:
            print(password, file=password_file)
    print("Log in with the following password: " + password)

    # The default locations are $HOME/.jupyter and $HOME/.local/share/jupyter, but these are
    # inconvenient because neither the Jupyter notebook nor the Android Studio file explorer
    # will display hidden files.
    for name in ["config", "data"]:
        dir_name = f"{files_dir}/jupyter/{name}"
        os.makedirs(dir_name, exist_ok=True)
        os.environ[f"JUPYTER_{name.upper()}_DIR"] = dir_name

    with open(os.environ["JUPYTER_CONFIG_DIR"] + "/jupyter_notebook_config.py",
              "w") as config_file:
        config = {
            "c.MultiKernelManager.kernel_manager_class": __name__ + ".ChaquopyManager",
            "c.NotebookApp.allow_remote_access": True,
            "c.NotebookApp.ip": "0.0.0.0",
            "c.NotebookApp.password": auth.passwd(password),
        }
        for key, value in config.items():
            print(f"{key} = {value!r}", file=config_file)
    notebookapp.main()


class ChaquopyManager(ioloop.IOLoopKernelManager):
    def _launch_kernel(self, kernel_cmd, **kwargs):
        if self.is_alive():
            raise RuntimeError("Can only run one kernel at a time")

        kwargs.pop("env", None)  # Unnecessary, and could cause complications.
        cwd = kwargs.pop("cwd")
        if kwargs:
            raise ValueError(f"Unknown kwargs: {kwargs}")

        # See ipykernel.kernelspec.make_ipkernel_cmd.
        match = re.search(fr"^{sys.executable} -m ipykernel_launcher -f (\S+)$",
                          " ".join(kernel_cmd))
        if not match:
            raise ValueError(f"Unknown command: {kernel_cmd}")
        app.startService(self._new_intent(jupyter_cwd=cwd,
                                          jupyter_connection_file=match.group(1)))

        # Return a non-None value so self.has_kernel will be true. This will be assigned to
        # self.kernel, but we override all the methods which access that.
        return True

    def _kill_kernel(self):
        app.stopService(self._new_intent())
        start_time = time.time()
        while self.is_alive():
            if time.time() > start_time + SHUTDOWN_TIMEOUT:
                raise RuntimeError("Failed to kill kernel")
            time.sleep(0.1)

    # Interruption would normally be done by sending SIGINT to the kernel process, but signals
    # can only be handled by the main thread, which is reserved for the Android event loop.
    def interrupt_kernel(self):
        raise RuntimeError("Kernel interrupt is not implemented. Use 'Restart' instead.")

    def signal_kernel(self, signum):
        pass

    def is_alive(self):
        am = app.getSystemService(app.ACTIVITY_SERVICE)
        for service_info in am.getRunningServices(Integer.MAX_VALUE).toArray():
            if service_info.service.getClassName() == KernelService.getClass().getName():
                return True
        return False

    def _new_intent(self, **extras):
        intent = Intent(app, KernelService.getClass())
        for key, value in extras.items():
            intent.putExtra(key, value)
        return intent
