package com.smartcalc.app.data.library.source

import java.io.InputStream

class SourceInput(val name: String, val mime: String?, val open: () -> InputStream)
