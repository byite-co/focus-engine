package co.byite.focus.engine.pipeline.scene

import co.byite.focus.engine.pipeline.RgbaFrame

/** Scene proxy on a 64×48 subsample of the RGBA frame (spec 6장). Runs at 1 Hz on the analysis thread. */
class SceneQuality {
    fun process(frame: RgbaFrame): SceneStats = SceneGrid.compute(frame.width, frame.height) { x, y -> frame.luma(x, y) }
}
