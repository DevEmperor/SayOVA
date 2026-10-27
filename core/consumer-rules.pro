# Tink registers its key managers reflectively and ships its own consumer rules; nothing else in
# :core is reached by reflection. kotlinx.serialization brings its own rules as well.
