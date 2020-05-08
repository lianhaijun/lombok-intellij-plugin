package de.plushnikov.intellij.plugin.processor.clazz;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map.Entry;
import java.util.Properties;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.intellij.openapi.project.Project;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementFactory;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiType;

import de.plushnikov.intellij.plugin.problem.ProblemBuilder;
import de.plushnikov.intellij.plugin.processor.LombokPsiElementUsage;
import de.plushnikov.intellij.plugin.processor.field.GetterFieldProcessor;
import de.plushnikov.intellij.plugin.psi.LombokLightFieldBuilder;
import de.plushnikov.intellij.plugin.util.PsiAnnotationUtil;
import lombok.InternationlCode;

/**
 * Inspect and validate @InternationlCode lombok annotation on a class Creates
 * fields of this class from properties file
 *
 * @author haijun.lian
 */
public class CommonCodeFieldProcessor extends AbstractClassProcessor {
	private static final Logger log = LoggerFactory.getLogger(CommonCodeFieldProcessor.class);
	private final String filePath = "internationl";

	public CommonCodeFieldProcessor(@NotNull GetterFieldProcessor getterFieldProcessor) {
		super(PsiField.class, InternationlCode.class);
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

		// 从文件中加载 国际化key
		target.addAll(createClassField(psiClass, psiAnnotation));
	}

	private Collection<PsiField> createClassField(PsiClass psiClass, PsiAnnotation psiAnnotation) {
		Collection<PsiField> result = new ArrayList<>();
		// 添加文件中的国际化属性值
		Project project = psiClass.getProject();
		String basePath = project.getBasePath();

		// 文件路径 src/main/resources
		String fileName = PsiAnnotationUtil.getStringAnnotationValue(psiAnnotation, "path");
		log.info("projectPath:" + basePath);
		File file = new File(basePath + File.separator + filePath + File.separator + fileName);
		if (!file.exists() || !file.isFile()) {
			return result;
		}
		Properties ps = new Properties();
		try {
			ps.load(new FileInputStream(file));
			for (Entry<Object, Object> en : ps.entrySet()) {
				String key = (String) en.getKey();
				String val = (String) en.getValue();
				// 根据文件中的key 和 val 生成国际化属性
				result.add(createField(psiClass, psiAnnotation, key, val));
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
		return result;
	}

	private LombokLightFieldBuilder createField(@NotNull PsiClass psiClass, @NotNull PsiAnnotation psiAnnotation,
			String fieldName, String value) {

		final Project project = psiClass.getProject();
		final PsiManager manager = psiClass.getContainingFile().getManager();

		final PsiElementFactory psiElementFactory = JavaPsiFacade.getElementFactory(project);
		String fieldType = psiClass.getName();
		final PsiType psiType = psiElementFactory.createTypeFromText(fieldType, psiClass);

		LombokLightFieldBuilder field = new LombokLightFieldBuilder(manager, fieldName, psiType)
				.withContainingClass(psiClass).withModifier(PsiModifier.PUBLIC).withModifier(PsiModifier.STATIC)
				.withModifier(PsiModifier.FINAL).withNavigationElement(psiAnnotation);

		String[] vs = value.split(",");
		String v1 = vs[0].trim();
		String v2 = "";
		if (vs.length > 1) {
			v2 = vs[1].trim();
		} else {
			v2 = vs[0].trim();
			v1 = "";
		}
		if ("".equals(v1)) {
			// 默认code码为 000000 成功
			v1 = "000000";
		}
		String initializerText = "new " + fieldType + "(\"" + v1 + "\",\"" + v2 + "\")";
		final PsiExpression initializer = psiElementFactory.createExpressionFromText(initializerText, psiClass);
		field.setInitializer(initializer);
		return field;
	}

	@Override
	public LombokPsiElementUsage checkFieldUsage(@NotNull PsiField psiField, @NotNull PsiAnnotation psiAnnotation) {
		return LombokPsiElementUsage.WRITE;
	}
}
