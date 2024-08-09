from __future__ import annotations
from ..wrapper import KotlinWrapper

from java import jclass


_arrangement = jclass("androidx.compose.foundation.layout.Arrangement").INSTANCE


class Arrangement(KotlinWrapper):
    """
    Used to specify the horizontal arrangement of the layout's children in layouts like [Row].
    """
    class Horizontal(KotlinWrapper):
        pass

    class Vertical(KotlinWrapper):
        pass

    class HorizontalOrVertical(Horizontal, Vertical):
        pass

    Start = _arrangement.getStart()
    End = _arrangement.getEnd()
    Top = _arrangement.getTop()
    Bottom = _arrangement.getBottom()
    Center = _arrangement.getCenter()

    SpaceEvenly = _arrangement.getSpaceEvenly()
    SpaceBetween = _arrangement.getSpaceBetween()
    SpaceAround = _arrangement.getSpaceAround()

    # # Type Overriding
    # Start.__class__ = Horizontal
    # End.__class__ = Horizontal
    # Top.__class__ = Vertical
    # Bottom.__class__ = Vertical
    # Center.__class__ = HorizontalOrVertical
    # SpaceEvenly.__class__ = HorizontalOrVertical
    # SpaceBetween.__class__ = HorizontalOrVertical
    # SpaceAround.__class__ = HorizontalOrVertical
