package com.featzima.rxmedia.extensions


fun Int.isFlagSet(flag: Int) = this.and(flag) == flag