"""
Firebase Cloud Functions for Python 3.14+ compatibility
Kinetic Eco Tracker - Backend Functions
"""

from firebase_functions import https_fn
from firebase_functions.options import set_global_options
from firebase_admin import initialize_app
from typing import Any

# For cost control, you can set the maximum number of containers that can be
# running at the same time. This helps mitigate the impact of unexpected
# traffic spikes by instead downgrading performance. This limit is a per-function
# limit. You can override the limit for each function using the max_instances
# parameter in the decorator, e.g. @https_fn.on_request(max_instances=5).
set_global_options(max_instances=10)

# Initialize Firebase Admin SDK
initialize_app()


@https_fn.on_request()
def on_request_example(req: https_fn.Request) -> https_fn.Response:
    """
    Example HTTP function that returns a greeting.
    Compatible with Python 3.14+ syntax.
    """
    return https_fn.Response("Hello world!")