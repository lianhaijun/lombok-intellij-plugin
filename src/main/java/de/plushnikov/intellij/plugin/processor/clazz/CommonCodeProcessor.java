package de.plushnikov.intellij.plugin.processor.clazz;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Collectors;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.intellij.codeInsight.daemon.impl.quickfix.SafeDeleteFix;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementFactory;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiType;
import com.intellij.psi.util.PsiTypesUtil;

import de.plushnikov.intellij.plugin.lombokconfig.ConfigKey;
import de.plushnikov.intellij.plugin.problem.ProblemBuilder;
import de.plushnikov.intellij.plugin.problem.ProblemEmptyBuilder;
import de.plushnikov.intellij.plugin.processor.LombokPsiElementUsage;
import de.plushnikov.intellij.plugin.processor.field.AccessorsInfo;
import de.plushnikov.intellij.plugin.processor.field.GetterFieldProcessor;
import de.plushnikov.intellij.plugin.psi.LombokLightFieldBuilder;
import de.plushnikov.intellij.plugin.psi.LombokLightMethodBuilder;
import de.plushnikov.intellij.plugin.util.LombokProcessorUtil;
import de.plushnikov.intellij.plugin.util.PsiAnnotationUtil;
import de.plushnikov.intellij.plugin.util.PsiClassUtil;
import de.plushnikov.intellij.plugin.util.PsiElementUtil;
import de.plushnikov.intellij.plugin.util.PsiMethodUtil;
import lombok.InternationlCode;

/**
 * Inspect and validate @InternationlCode lombok annotation on a class Creates fields
 * of this class from properties file
 *
 * @author haijun.lian
 */
public class CommonCodeProcessor extends AbstractClassProcessor {

	private final String CODE = "code";
	private final String KEY = "key";
	
	private final GetterFieldProcessor fieldProcessor;

	public CommonCodeProcessor(@NotNull GetterFieldProcessor getterFieldProcessor) {
		super(PsiMethod.class, InternationlCode.class);
		this.fieldProcessor = getterFieldProcessor;
	}

	@Override
	protected boolean validate(@NotNull PsiAnnotation psiAnnotation, @NotNull PsiClass psiClass,
			@NotNull ProblemBuilder builder) {
		String lazy = "lazy";
		final boolean result = validateAnnotationOnRightType(psiClass, builder);
		if (PsiAnnotationUtil.getBooleanAnnotationValue(psiAnnotation, lazy, false)) {
			builder.addWarning("'lazy' is not supported for @InternationlCode on a type");
		}
		return result;
	}

	private boolean validateAnnotationOnRightType(@NotNull PsiClass psiClass, @NotNull ProblemBuilder builder) {
		boolean result = true;
		if (psiClass.isAnnotationType() || psiClass.isInterface() || psiClass.isEnum()) {
			builder.addError("'@InternationlCode' is only supported on a class type");
			result = false;
		}
		return result;
	}

	protected void generatePsiElements(@NotNull PsiClass psiClass, @NotNull PsiAnnotation psiAnnotation,
			@NotNull List<? super PsiElement> target) {
		// 创建 code 和 key 的 getter 方法
		target.addAll(createFieldGetters(psiClass, psiAnnotation, PsiModifier.PUBLIC));

		// 私有化类构造器
		target.addAll(createConstructorMethod(psiClass, psiAnnotation));

	}

	private Collection<PsiMethod> createConstructorMethod(PsiClass psiClass, PsiAnnotation psiAnnotation) {
		final Collection<PsiField> params = new ArrayList<>();
		final Collection<PsiMethod> methods = new ArrayList<>();
		methods.addAll(createConstructorMethod(psiClass, psiAnnotation, params));

		params.add(createField(psiClass, psiAnnotation, CODE));
		params.add(createField(psiClass, psiAnnotation, KEY));
		methods.addAll(createConstructorMethod(psiClass, psiAnnotation, params));

		return methods;
	}

	@NotNull
	public Collection<PsiMethod> createFieldGetters(@NotNull PsiClass psiClass, @NotNull PsiAnnotation psiAnnotation,
			@NotNull String methodModifier) {
		Collection<PsiMethod> result = new ArrayList<>();
		final Collection<PsiField> getterFields = filterGetterFields(psiClass, psiAnnotation);
		for (PsiField getterField : getterFields) {
			result.add(fieldProcessor.createGetterMethod(getterField, psiClass, methodModifier));
		}
		return result;
	}

	@NotNull
	private Collection<PsiField> filterGetterFields(@NotNull PsiClass psiClass, @NotNull PsiAnnotation psiAnnotation) {
		final Collection<PsiField> getterFields = new ArrayList<>();

		final Collection<PsiMethod> classMethods = PsiClassUtil.collectClassMethodsIntern(psiClass);
		filterToleratedElements(classMethods);

		String prefix = "get";
		String codeMethod = buildName(prefix, CODE);
		String keyMethod = buildName(prefix, KEY);

		if (!PsiMethodUtil.hasSimilarMethod(classMethods, codeMethod, 0)) {
			// 新增getCode
			getterFields.add(createField(psiClass, psiAnnotation, CODE));
		}
		if (!PsiMethodUtil.hasSimilarMethod(classMethods, keyMethod, 0)) {
			// 新增getKey
			getterFields.add(createField(psiClass, psiAnnotation, KEY));
		}

		return getterFields;
	}

	private String buildName(String prefix, String suffix) {
		return prefix + StringUtil.capitalize(suffix);
	}

	private LombokLightFieldBuilder createField(@NotNull PsiClass psiClass, @NotNull PsiAnnotation psiAnnotation,
			String fieldName) {

		final Project project = psiClass.getProject();
		final PsiManager manager = psiClass.getContainingFile().getManager();

		final PsiElementFactory psiElementFactory = JavaPsiFacade.getElementFactory(project);
		String fieldType = "java.lang.String";
		final PsiType psiType = psiElementFactory.createTypeFromText(fieldType, psiClass);

		LombokLightFieldBuilder field = new LombokLightFieldBuilder(manager, fieldName, psiType)
				.withContainingClass(psiClass).withModifier(PsiModifier.PRIVATE).withNavigationElement(psiAnnotation);

		return field;
	}

	@NotNull
	private Collection<PsiMethod> createConstructorMethod(@NotNull PsiClass psiClass,
			@NotNull PsiAnnotation psiAnnotation, @NotNull Collection<PsiField> params) {
		List<PsiMethod> methods = new ArrayList<>();

		boolean hasConstructor = !validateIsConstructorNotDefined(psiClass, params, ProblemEmptyBuilder.getInstance());

		final String constructorVisibility = PsiModifier.PRIVATE;

		if (!hasConstructor) {
			final PsiMethod constructor = createConstructor(psiClass, constructorVisibility, false, params,
					psiAnnotation);
			methods.add(constructor);
		}

		return methods;
	}

	private boolean validateIsConstructorNotDefined(@NotNull PsiClass psiClass, @NotNull Collection<PsiField> params,
			@NotNull ProblemBuilder builder) {
		boolean result = true;

		final List<PsiType> paramTypes = new ArrayList<>(params.size());
		for (PsiField param : params) {
			paramTypes.add(param.getType());
		}

		final Collection<PsiMethod> definedConstructors = PsiClassUtil.collectClassConstructorIntern(psiClass);
		final String constructorName = getConstructorName(psiClass);

		final PsiMethod existedMethod = findExistedMethod(definedConstructors, constructorName, paramTypes);
		if (null != existedMethod) {
			if (paramTypes.isEmpty()) {
				builder.addError("Constructor without parameters is already defined", new SafeDeleteFix(existedMethod));
			} else {
				builder.addError(String.format("Constructor with %d parameters is already defined", paramTypes.size()),
						new SafeDeleteFix(existedMethod));
			}
			result = false;
		}

		return result;
	}

	private PsiMethod createConstructor(@NotNull PsiClass psiClass,
			@PsiModifier.ModifierConstant @NotNull String modifier, boolean useJavaDefaults,
			@NotNull Collection<PsiField> params, @NotNull PsiAnnotation psiAnnotation) {
		LombokLightMethodBuilder constructorBuilder = new LombokLightMethodBuilder(psiClass.getManager(),
				getConstructorName(psiClass)).withConstructor(true).withContainingClass(psiClass)
						.withNavigationElement(psiAnnotation).withModifier(modifier);

		final List<String> fieldNames = new ArrayList<>();
		final AccessorsInfo classAccessorsInfo = AccessorsInfo.build(psiClass);
		for (PsiField psiField : params) {
			final AccessorsInfo paramAccessorsInfo = AccessorsInfo.build(psiField, classAccessorsInfo);
			fieldNames.add(paramAccessorsInfo.removePrefix(psiField.getName()));
		}

		if (!fieldNames.isEmpty()) {
			boolean addConstructorProperties = configDiscovery
					.getBooleanLombokConfigProperty(ConfigKey.ANYCONSTRUCTOR_ADD_CONSTRUCTOR_PROPERTIES, psiClass);
			if (addConstructorProperties || !configDiscovery.getBooleanLombokConfigProperty(
					ConfigKey.ANYCONSTRUCTOR_SUPPRESS_CONSTRUCTOR_PROPERTIES, psiClass)) {
				final String constructorPropertiesAnnotation = "java.beans.ConstructorProperties( {"
						+ fieldNames.stream().collect(Collectors.joining("\", \"", "\"", "\"")) + "} ) ";
				constructorBuilder.withAnnotation(constructorPropertiesAnnotation);
			}
		}

		constructorBuilder.withAnnotations(LombokProcessorUtil.getOnX(psiAnnotation, "onConstructor"));

		if (!useJavaDefaults) {
			final Iterator<String> fieldNameIterator = fieldNames.iterator();
			final Iterator<PsiField> fieldIterator = params.iterator();
			while (fieldNameIterator.hasNext() && fieldIterator.hasNext()) {
				constructorBuilder.withParameter(fieldNameIterator.next(), fieldIterator.next().getType());
			}
		}

		final StringBuilder blockText = new StringBuilder();

		final Iterator<String> fieldNameIterator = fieldNames.iterator();
		final Iterator<PsiField> fieldIterator = params.iterator();
		while (fieldNameIterator.hasNext() && fieldIterator.hasNext()) {
			final PsiField param = fieldIterator.next();
			final String fieldName = fieldNameIterator.next();
			final String fieldInitializer = useJavaDefaults ? PsiTypesUtil.getDefaultValueOfType(param.getType())
					: fieldName;
			blockText.append(String.format("this.%s = %s;\n", param.getName(), fieldInitializer));
		}

		constructorBuilder.withBody(PsiMethodUtil.createCodeBlockFromText(blockText.toString(), constructorBuilder));

		return constructorBuilder;
	}

	@NotNull
	private String getConstructorName(@NotNull PsiClass psiClass) {
		return StringUtil.notNullize(psiClass.getName());
	}

	@Nullable
	private PsiMethod findExistedMethod(final Collection<PsiMethod> definedMethods, final String methodName,
			final List<PsiType> paramTypes) {
		for (PsiMethod method : definedMethods) {
			if (PsiElementUtil.methodMatches(method, null, null, methodName, paramTypes)) {
				return method;
			}
		}
		return null;
	}

	@Override
	public LombokPsiElementUsage checkFieldUsage(@NotNull PsiField psiField, @NotNull PsiAnnotation psiAnnotation) {
		final PsiClass containingClass = psiField.getContainingClass();
		if (null != containingClass) {
			if (PsiClassUtil.getNames(filterGetterFields(containingClass, psiAnnotation))
					.contains(psiField.getName())) {
				return LombokPsiElementUsage.READ;
			}
		}
		return LombokPsiElementUsage.NONE;
	}
}
