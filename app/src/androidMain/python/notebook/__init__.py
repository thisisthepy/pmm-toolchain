import asyncio
import selectors
import signal
import sys
import types

from tornado.platform.asyncio import AnyThreadEventLoopPolicy


# Allow notebook to be run on a non-main thread.
asyncio.set_event_loop_policy(AnyThreadEventLoopPolicy())
signal.signal = lambda signum, handler: signal.getsignal(signum)
