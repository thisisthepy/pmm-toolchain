from __future__ import annotations
from ..wrapper import KotlinWrapper

from java import jclass


_alignment = jclass("androidx.compose.ui.Alignment").Companion
_absolute_alignment = jclass("androidx.compose.ui.AbsoluteAlignment").INSTANCE


class Alignment(KotlinWrapper):
    """
    An interface to calculate the position of a sized box inside an available space.
    Alignment is often used to define the alignment of a layout inside a parent layout.
    """
    TopStart = _alignment.getTopStart()
    TopCenter = _alignment.getTopCenter()
    TopEnd = _alignment.getTopEnd()
    CenterStart = _alignment.getCenterStart()
    Center = _alignment.getCenter()
    CenterEnd = _alignment.getCenterEnd()
    BottomStart = _alignment.getBottomStart()
    BottomCenter = _alignment.getBottomCenter()
    BottomEnd = _alignment.getBottomEnd()

    class Vertical(KotlinWrapper):
        Top = _alignment.getTop()
        CenterVertically = _alignment.getCenterVertically()
        Bottom = _alignment.getBottom()

    class Horizontal(KotlinWrapper):
        Start = _alignment.getStart()
        CenterHorizontally = _alignment.getCenterHorizontally()
        End = _alignment.getEnd()


# Type Overriding
# Alignment.TopStart.__class__ = Alignment
# Alignment.TopCenter.__class__ = Alignment
# Alignment.TopEnd.__class__ = Alignment
# Alignment.CenterStart.__class__ = Alignment
# Alignment.Center.__class__ = Alignment
# Alignment.CenterEnd.__class__ = Alignment
# Alignment.BottomStart.__class__ = Alignment
# Alignment.BottomCenter.__class__ = Alignment
# Alignment.BottomEnd.__class__ = Alignment
# _Vertical = Alignment.Vertical
# _Vertical.Top.__class__ = _Vertical
# _Vertical.CenterVertically.__class__ = _Vertical
# _Vertical.Bottom.__class__ = _Vertical
# _Horizontal = Alignment.Horizontal
# _Horizontal.Start.__class__ = _Horizontal
# _Horizontal.CenterHorizontally.__class__ = _Horizontal
# _Horizontal.End.__class__ = _Horizontal


class AbsoluteAlignment:
    """
    A collection of common Alignments unaware of the layout direction.
    """
    TopLeft = _absolute_alignment.getTopLeft()
    TopLeft.__class__ = Alignment
    TopRight = _absolute_alignment.getTopRight()
    TopRight.__class__ = Alignment
    CenterLeft = _absolute_alignment.getCenterLeft()
    CenterLeft.__class__ = Alignment
    CenterRight = _absolute_alignment.getCenterRight()
    CenterRight.__class__ = Alignment
    BottomLeft = _absolute_alignment.getBottomLeft()
    BottomLeft.__class__ = Alignment
    BottomRight = _absolute_alignment.getBottomRight()
    BottomRight.__class__ = Alignment
    Left = _absolute_alignment.getLeft()
    Left.__class__ = Alignment
    Right = _absolute_alignment.getRight()
    Right.__class__ = Alignment


#TODO: Add BiasAlignment, BiasAbsoluteAlignment
