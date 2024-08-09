from __future__ import annotations
from ..wrapper import KotlinWrapper

from java import jclass


_modifier = jclass("androidx.compose.ui.Modifier").Companion


class Modifier(KotlinWrapper):
    """ Compose Modifiers

    Modifiers allow you to decorate or augment a composable. Modifiers let you do these sorts of things:

    Change the composable's size, layout, behavior, and appearance
    Add information, like accessibility labels
    Process user input
    Add high-level interactions, like making an element clickable, scrollable, draggable, or zoomable
    Modifiers are standard Kotlin objects. Create a modifier by calling one of the Modifier class functions
    """
    def __new__(cls, kotlin_object=None):
        return get_instance() if kotlin_object is None else super().__new__(cls, kotlin_object)


#Modifier.__class__ = _modifier.__class__
#_modifier.__class__ = Modifier


def get_instance():
    return _modifier
