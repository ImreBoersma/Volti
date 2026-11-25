# R8 rules for the test APK, which is shrunk too when the tested build is minified.
# Compile-time annotations from errorprone refer to javax.lang.model, which Android doesn't have.
-dontwarn javax.lang.model.element.Modifier
