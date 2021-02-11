package org.openpnp.machine.reference.wizards;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.border.TitledBorder;

import org.openpnp.gui.components.ComponentDecorators;
import org.openpnp.gui.support.AbstractConfigurationWizard;
import org.openpnp.gui.support.DoubleConverter;
import org.openpnp.model.Configuration;
import org.openpnp.spi.PnpJobProcessor;
import org.openpnp.spi.base.AbstractMachine;

import com.jgoodies.forms.layout.ColumnSpec;
import com.jgoodies.forms.layout.FormLayout;
import com.jgoodies.forms.layout.FormSpecs;
import com.jgoodies.forms.layout.RowSpec;

@SuppressWarnings("serial")
public class ReferencePasteJobProcessorWizard extends AbstractConfigurationWizard {
    private final PnpJobProcessor jobProcessor;
    
    private JLabel lblAmount;
    private JTextField dispenseAmount;
    private JLabel lblRetract;
    private JTextField retractAmount;
    private JPanel panelProperties;
    
    public ReferencePasteJobProcessorWizard(AbstractMachine machine, PnpJobProcessor jobProcessor) {
        this.jobProcessor = jobProcessor;
        
        panelProperties = new JPanel();
        panelProperties.setBorder(new TitledBorder(null, "Properties", TitledBorder.LEADING, TitledBorder.TOP, null, null));
        contentPanel.add(panelProperties);
        panelProperties.setLayout(new FormLayout(new ColumnSpec[] {
                FormSpecs.RELATED_GAP_COLSPEC,
                FormSpecs.DEFAULT_COLSPEC,
                FormSpecs.RELATED_GAP_COLSPEC,
                FormSpecs.DEFAULT_COLSPEC,},
            new RowSpec[] {
                FormSpecs.RELATED_GAP_ROWSPEC,
                FormSpecs.DEFAULT_ROWSPEC,
                FormSpecs.RELATED_GAP_ROWSPEC,
                FormSpecs.DEFAULT_ROWSPEC,}));
        
        lblAmount = new JLabel("Dispense Amount");
        panelProperties.add(lblAmount, "2, 2, right, default");
        
        dispenseAmount = new JTextField();
        panelProperties.add(dispenseAmount, "4, 2");
        dispenseAmount.setColumns(5);
        
        lblRetract = new JLabel("Retract Amount");
        panelProperties.add(lblRetract, "2, 4, right, default");
        
        retractAmount = new JTextField();
        panelProperties.add(retractAmount, "4, 4");
        retractAmount.setColumns(5);
    }

    @Override
    public void createBindings() {
        DoubleConverter doubleConverter =
                new DoubleConverter(Configuration.get().getLengthDisplayFormat());
        addWrappedBinding(jobProcessor, "dispenseAmount", dispenseAmount, "text", doubleConverter);
        addWrappedBinding(jobProcessor, "retractAmount", retractAmount, "text", doubleConverter);
        
        ComponentDecorators.decorateWithAutoSelect(dispenseAmount);
        ComponentDecorators.decorateWithAutoSelect(retractAmount);
    }

}
