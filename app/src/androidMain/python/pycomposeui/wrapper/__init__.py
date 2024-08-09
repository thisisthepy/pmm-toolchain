class KotlinWrapper:
    def __new__(cls, kotlin_object):
        kotlin_object.__class__ = cls
        return kotlin_object
